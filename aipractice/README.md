# AI Practice - 검색 평가와 신뢰 가능한 RAG 챗봇

2주차의 검색 정확도 실험과 3주차의 신뢰성·UX 요구사항을 한 프로젝트에서 재현하는
학습용 Spring Boot/Spring AI 애플리케이션입니다.

## 구현된 기능

- 글자 기준 `chunkSize`와 `overlap`을 바꿔 같은 문서를 여러 profile로 적재
- Vector, PostgreSQL keyword, Hybrid(RRF) 검색 비교
- Top-K, similarity threshold, 제목·카테고리·profile metadata 필터
- 실제 정답 source가 표시된 질문 세트로 Recall@K, HitRate@K, MRR 계산
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

## 3. Recall@K 평가표 만들기

애플리케이션을 실행한 상태에서 아래 스크립트 한 번으로 sample 문서를 두 profile로
적재하고 질문 6개를 평가합니다.

```powershell
.\scripts\run-benchmark.ps1
```

실제 결과는 다음 두 파일에 저장됩니다.

- `build/reports/rag-evaluation.md`: 제출용 비교표
- `build/reports/rag-evaluation.json`: 질문별 검색 source와 최초 정답 순위

평가 데이터와 비교 설정은
`src/main/resources/evaluation/questions.json`에서 수정합니다. 예를 들어 한 질문의 정답
문서가 두 개면 `relevantSources`에 두 파일을 모두 넣습니다.

지표의 의미는 다음과 같습니다.

```text
Recall@K = Top-K 안에서 찾은 정답 문서 수 / 전체 정답 문서 수
HitRate@K = 정답을 하나라도 찾은 질문 수 / 전체 질문 수
MRR       = 질문별 첫 정답 순위의 역수(1/rank) 평균
```

Recall만 같다면 MRR이 높은 설정이 정답을 앞쪽에 올린 것입니다. Top-K를 키우면 Recall은
대체로 오르지만 LLM context 비용과 잡음도 증가합니다. 따라서 `Recall@5`가 `Recall@10`과
같고 MRR도 충분히 높다면 Top-K 5가 보통 더 경제적입니다.

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
3. `RetrievalEvaluationService`는 답변 품질이 아니라 **검색 품질**만 측정합니다.
4. `GroundedAnswerGenerator`는 Few-shot, PromptTemplate, 인용 검증을 담당합니다.
5. `RagChatService`는 검색 결과 0건 사전 보류, 대화 이력, streaming timeout을 담당합니다.

PostgreSQL의 `simple` text search는 한국어 형태소 분석기가 아닙니다. 띄어쓰기 단어가
정확히 겹치는 질의에는 강하지만 표현이 바뀌면 약해집니다. 이 차이를 vector 검색이
보완하고, 둘 중 한쪽만 잘 찾는 문서를 Hybrid가 살리는지를 평가표에서 확인하면 됩니다.
