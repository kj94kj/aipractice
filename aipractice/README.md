# AI Practice - RAG 평가 자동화와 신뢰 가능한 챗봇

2주차의 검색 정확도 실험과 3주차의 신뢰성·UX 요구사항을 한 프로젝트에서 재현하는
학습용 Spring Boot/Spring AI 애플리케이션입니다.

## 구현된 기능

- 글자 기준 `chunkSize`와 `overlap`을 바꿔 같은 문서를 여러 profile로 적재
- Vector, PostgreSQL keyword, Hybrid(RRF) 검색 비교
- Top-K, similarity threshold, 제목·카테고리·profile metadata 필터
- 36개 gold 질문 세트로 Recall@3, Recall@5, MRR 자동 계산
- 검색 시간, LLM 생성 시간, 오류율, 답변 보류 정확도 기록
- 인용 기반 Faithfulness proxy와 정답 핵심 문구 기반 Answer Relevancy proxy
- 검색 변경과 프롬프트 변경을 분리한 baseline/candidate 비교
- 답변의 `[문서 N]` 인용과 파일·페이지·chunk 글자 위치 반환
- 근거가 없으면 LLM을 호출하지 않고 답변 보류
- Few-shot과 `PromptTemplate`을 사용한 문서 전용 프롬프트
- 일반 JSON 답변과 SSE streaming 답변
- `conversationId`별 최근 12개 메시지 기억 및 후속 질문 검색 보강
- AI 재시도, 45초 timeout, 빈 응답·인용 없는 응답·API 오류 처리

## 전체 흐름

```text
TXT/PDF
  → CharacterChunker(size, overlap)
  → metadata(title, category, page, chunk 위치, profile)
  → OpenAI Embedding → pgvector

질문
  ├─ Vector Search ─────┐
  ├─ Keyword Search ────┼─ RRF Hybrid → threshold/filter/top-k
  └─ 이전 사용자 질문 ─┘                  ↓
                              근거 전용 PromptTemplate
                                         ↓
                         답변 + [문서 N] + sources
```

## 준비와 실행

필요한 것은 Java 17, Docker Desktop, OpenAI API key입니다.

```powershell
$env:OPENAI_API_KEY="본인의 API Key"
docker compose up -d
.\gradlew.bat bootRun
```

테스트는 실제 OpenAI 호출 없이 실행됩니다.

```powershell
.\gradlew.bat test
```

## 1. Chunk 크기와 overlap 실험

같은 문서를 서로 다른 profile로 저장해야 비교가 섞이지 않습니다.

```powershell
curl.exe -X POST `
  -F "file=@src/main/resources/sample-documents/company.txt" `
  -F "title=회사 연차 규정" -F "category=hr" `
  -F "chunkSize=300" -F "overlap=30" `
  http://localhost:8080/api/rag/documents
```

응답의 `profile`은 `c300-o30` 형태입니다. 같은 파일·profile은 SHA-256 기반의 동일한
UUID를 사용하므로 다시 업로드해도 동일 chunk가 갱신되고 중복되지 않습니다.

현재 chunk 단위는 **글자 수**입니다. 토큰 단위 splitter와 달리 위치(`chunkStart`,
`chunkEnd`)를 사람이 바로 확인할 수 있어 학습과 평가에 유리합니다. 한국어 모델 비용을
정밀하게 최적화할 때는 토큰 단위로 교체할 수 있습니다.

## 2. 검색 조건 직접 비교

```powershell
curl.exe -X POST `
  -H "Content-Type: application/json; charset=UTF-8" `
  -d '{"question":"남은 연차는 며칠까지 넘길 수 있나요?","mode":"HYBRID","topK":5,"similarityThreshold":0.5,"category":"hr","profile":"c300-o30"}' `
  http://localhost:8080/api/rag/search
```

`mode`는 `VECTOR`, `KEYWORD`, `HYBRID` 중 하나입니다. Vector 결과에는
`vectorScore`, keyword 결과에는 `keywordScore`, Hybrid 결과에는 양쪽 점수와 RRF
최종 순위 점수가 반환됩니다.

Similarity threshold는 vector 후보에 적용합니다. Keyword에는 cosine similarity가
없으므로 적용하지 않으며, Hybrid에서는 threshold를 통과한 vector 후보와 keyword
후보를 합칩니다.

## 3. 4주차: RAG 자동 평가 실행

애플리케이션을 실행한 상태에서 아래 스크립트 한 번으로 sample 문서를 두 profile로
적재하고 36개 질문을 세 설정에 대해 일괄 평가합니다. 30개는 답변 가능 질문이고,
6개는 모델이 올바르게 답변을 보류하는지 확인하는 답변 불가 질문입니다.

```powershell
.\scripts\run-benchmark.ps1
```

실제 결과는 다음 세 파일에 저장됩니다.

- `build/reports/rag-evaluation.md`: 제출용 요약과 직전 설정 대비 delta
- `build/reports/rag-evaluation.csv`: 설정·질문별 상세 결과
- `build/reports/rag-evaluation.json`: 질문별 검색 source와 최초 정답 순위

LLM 비용 없이 검색 설정만 빠르게 반복하려면 다음처럼 실행합니다.

```powershell
.\scripts\run-benchmark.ps1 -SkipAnswerEvaluation
```

평가 데이터와 비교 설정은
`src/main/resources/evaluation/questions.json`에서 수정합니다. 예를 들어 한 질문의 정답
문서가 두 개면 `relevantSources`에 두 파일을 모두 넣습니다.

세 기본 실험은 한 번에 여러 변수를 바꾸지 않도록 설계했습니다.

| 설정 | 검색 변경 | 프롬프트 변경 | 비교 목적 |
|---|---|---|---|
| `baseline-vector` | Vector | Baseline | 기준 |
| `search-improved-hybrid` | Hybrid | Baseline | 검색 변경 효과만 격리 |
| `prompt-improved-strict` | Hybrid | Strict citation | 프롬프트 변경 효과만 격리 |

지표의 의미는 다음과 같습니다.

```text
Recall@3  = 상위 3개에서 찾은 정답 문서 수 / 전체 정답 문서 수
Recall@5  = 상위 5개에서 찾은 정답 문서 수 / 전체 정답 문서 수
MRR       = 질문별 첫 정답 문서 순위의 역수(1/rank) 평균
오류율    = 검색 또는 답변 생성 오류가 발생한 질문 수 / 전체 질문 수
```

`relevantSources`의 파일명은 이 실습에서 사용하는 논리 문서 ID입니다. chunk UUID는
profile마다 달라질 수 있으므로 gold label로 쓰지 않습니다. 답변 불가 질문은 정답 문서가
없기 때문에 Recall 평균에서는 제외하고, 대신 답변 보류 정확도에 반영합니다.

### Faithfulness와 Answer Relevancy

이 프로젝트의 자동 점수는 API 비용이 없고 매번 같은 결과를 내는 **proxy**입니다.

- `Faithfulness proxy`: 답변의 각 문장에 실제 검색 결과 범위 안의 `[문서 N]` 인용이
  있는 비율입니다. 인용 형식 준수는 측정하지만, 문장이 문서에서 논리적으로 도출되는지까지
  증명하지는 않습니다.
- `Answer Relevancy proxy`: 사람이 gold dataset에 적은 `answerKeyPhrases`가 답변에
  포함된 비율입니다. 핵심 답 누락을 잘 잡지만 동의어·의역에는 약합니다.
- `Abstention Accuracy`: 답할 수 있는 질문에는 답하고, 답할 수 없는 질문에는
  `근거 부족`으로 보류했는지 측정합니다.

실무 수준의 의미 평가는 RAGAS/DeepEval 또는 별도 LLM judge에 `question`, `retrieved
context`, `answer`, `reference answer`를 주고 0~1 점수와 판정 근거를 구조화 출력으로
받는 방식으로 확장합니다. 이때 **답변을 만든 모델과 다른 judge 모델 사용**, judge prompt
버전 고정, 사람이 채점한 20~50건과의 상관관계 확인이 필요합니다. LLM judge 결과도
확률적이므로 이 proxy와 사람 표본 검수를 함께 두는 편이 안전합니다.

> 이 저장소에는 측정값을 미리 적어 두지 않습니다. Embedding 모델, 적재 문서, 질문이
> 바뀌면 수치도 달라지기 때문입니다. `run-benchmark.ps1`이 현재 환경의 실제 수치와
> 질문별 근거를 생성하므로 그 결과로 개선 전후를 설명해야 합니다.

## 4. 출처가 있는 일반 답변

```powershell
curl.exe -X POST `
  -H "Content-Type: application/json; charset=UTF-8" `
  -d '{"conversationId":"demo-1","question":"남은 연차는 얼마나 이월해?","mode":"HYBRID","topK":5,"similarityThreshold":0.5,"category":"hr","profile":"c300-o30"}' `
  http://localhost:8080/api/rag/chat
```

응답에는 `answer`, `abstained`, 검색 설정, 그리고 다음 위치 정보가 포함됩니다.

```json
{
  "answer": "남은 연차는 최대 5일까지 이월할 수 있습니다. [문서 1]",
  "abstained": false,
  "sources": [
    {
      "citationNumber": 1,
      "source": "company.txt",
      "page": "1",
      "chunkIndex": 0,
      "chunkStart": 0,
      "chunkEnd": 112,
      "score": 0.82
    }
  ]
}
```

검색 결과가 없으면 LLM 호출 없이 아래 답을 반환합니다.

```text
근거 부족: 제공된 문서에서 답을 찾을 수 없습니다.
```

검색 결과가 있더라도 모델이 인용 없이 답하면 `502 Bad Gateway`로 처리합니다.

## 5. Streaming과 대화 이력

```powershell
curl.exe -N -X POST `
  -H "Content-Type: application/json; charset=UTF-8" `
  -d '{"conversationId":"demo-1","question":"언제까지 사용해야 해?","mode":"HYBRID","topK":5,"category":"hr","profile":"c300-o30"}' `
  http://localhost:8080/api/rag/chat/stream
```

SSE는 `SOURCES → TOKEN 여러 개 → DONE` 순서이며 실패 시 `ERROR` 이벤트로 끝납니다.
같은 `conversationId`를 보내면 이전 대화가 prompt에 포함되고, 직전 사용자 질문과 현재
후속 질문을 함께 검색합니다. 메모리는 현재 프로세스 안에만 있으므로 운영 환경에서는
Redis/JDBC 기반 `ChatMemoryRepository`로 바꾸는 것이 좋습니다.

대화 삭제:

```powershell
curl.exe -X DELETE http://localhost:8080/api/rag/chat/conversations/demo-1
```

## 학습 포인트와 주의점

1. `CharacterChunker`에서 chunk 경계와 overlap이 Recall에 미치는 영향을 확인합니다.
2. `RagSearchService`에서 metadata filter와 RRF 공식을 확인합니다. 서로 단위가 다른
   cosine/keyword 점수를 직접 더하지 않는 이유가 핵심입니다.
3. `RetrievalEvaluationService`에서 검색과 답변 생성의 시간·오류·점수가 서로 분리되는지
   확인합니다.
4. `AnswerQualityEvaluator`에서 저렴한 결정적 proxy의 장점과 한계를 확인합니다.
5. `GroundedAnswerGenerator`의 `BASELINE`과 `STRICT_CITATION`을 비교해 프롬프트 변경의
   효과를 검색 변경과 분리합니다.
6. `RagChatService`는 검색 결과 0건 사전 보류, 대화 이력, streaming timeout을 담당합니다.

### 이번 주 학습 순서

1. **Gold dataset 설계**: 실제 사용자 표현, 쉬운 질문/패러프레이즈/답변 불가 질문의
   비율과 정답 문서 ID 라벨링 방법을 익힙니다.
2. **검색 지표**: Recall@K, Precision@K, MRR, nDCG의 차이를 익히고 이 실습에서는
   정답 문서를 놓치지 않는 것이 우선이라 Recall과 MRR을 선택한 이유를 설명합니다.
3. **생성 지표**: Faithfulness(근거 충실도), Answer Relevancy(질문에 대한 관련성),
   correctness(정답 일치도)가 서로 다른 축임을 익힙니다.
4. **실험 설계**: 한 번에 한 변수만 변경하고 같은 dataset·모델·temperature로 반복해
   baseline 대비 delta를 해석합니다.
5. **운영 지표**: 평균뿐 아니라 p95 latency, timeout, 오류율, 토큰 비용도 품질과 함께
   회귀 기준에 넣는 방법을 학습합니다.

제출 보고서에서는 “Hybrid가 Recall@3를 얼마나 바꿨는가”, “Strict prompt가 검색 점수는
그대로 둔 채 Faithfulness/보류 정확도를 바꿨는가”, “그 대가로 지연시간과 오류율이 얼마나
변했는가”를 각각 한 문단으로 설명하면 검색 품질과 최종 답변 품질을 구분했다는 것이
명확해집니다.

PostgreSQL의 `simple` text search는 한국어 형태소 분석기가 아닙니다. 띄어쓰기 단어가
정확히 겹치는 질의에는 강하지만 표현이 바뀌면 약해집니다. 이 차이를 vector 검색이
보완하고, 둘 중 한쪽만 잘 찾는 문서를 Hybrid가 살리는지를 평가표에서 확인하면 됩니다.
