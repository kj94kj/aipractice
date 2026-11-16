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
docker compose up -d pgvector
.\gradlew.bat bootRun
```

애플리케이션과 Prometheus까지 컨테이너로 실행할 때는 `docker compose up --build -d`를
사용합니다. 두 실행 방식을 동시에 사용하면 8080 포트가 충돌하므로 하나만 선택합니다.

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

## 5주차 실습: 운영 가능한 RAG 문서 관리

이번 실습의 목표는 단순히 파일을 추가하는 현재 구조를 **문서의 생성·수정·삭제·재색인과
운영 상태를 관리하는 구조**로 확장하는 것입니다. 핵심은 CRUD API 개수보다 원본 문서,
버전, 여러 chunk, pgvector 레코드가 장애나 동시 요청 뒤에도 서로 일치하는지 설명하고
검증하는 데 있습니다.

> 아래 기능은 현재 프로젝트에 구현되어 있습니다. `rag_document`와
> `rag_document_version`은 Flyway가 생성하고, 원본 파일은 버전 테이블에 보관되므로 파일을
> 다시 올리지 않고도 chunk profile을 바꿔 재색인할 수 있습니다.

### 바로 실행하기

전체 환경을 Docker Compose로 실행합니다.

```powershell
$env:OPENAI_API_KEY="본인의 API Key"
docker compose up --build -d
docker compose ps
```

- API와 Actuator: `http://localhost:8080`
- Prometheus: `http://localhost:9090`
- Readiness: `http://localhost:8080/actuator/health/readiness`

애플리케이션을 IDE에서 실행하고 DB만 Docker로 띄우려면 다음 명령을 사용합니다.

```powershell
docker compose up -d pgvector
.\gradlew.bat bootRun
```

생성 → 중복 확인 → 수정 → 재색인 → 버전 조회 → 지표 확인 → 삭제를 한 번에 실습하는
스크립트도 제공합니다.

```powershell
.\scripts\run-document-lifecycle.ps1

# 실행 뒤 데이터를 남겨 직접 DB와 검색 결과를 확인하려면
.\scripts\run-document-lifecycle.ps1 -KeepDocument
```

스크립트를 실행하기 전에 app의 readiness가 `UP`인지 확인합니다. Embedding을 실제로
생성하므로 유효한 OpenAI API key가 필요합니다.

#### `relation "rag_document" does not exist`가 발생할 때

Spring Boot 4에서는 `flyway-core`만 추가하면 migration이 자동 실행되지 않으므로 이
프로젝트는 `spring-boot-starter-flyway`를 사용합니다. 또한 기존 `vector_store`가 있는
DB도 V1부터 migration하도록 baseline version을 0으로 설정했습니다. 수정된 코드로 앱을
완전히 재시작하면 시작 로그에 다음 순서가 나타나야 합니다.

```text
Successfully baselined schema with version: 0
Migrating schema "public" to version "1 - create rag document catalog"
Successfully applied 1 migration
```

로컬 실행이면 실행 중인 앱을 종료한 뒤 `.\gradlew.bat bootRun`을 다시 실행합니다. Compose
실행이면 이전 image를 재사용하지 않도록 `docker compose up --build -d`를 실행합니다.

### 최종 완료 조건

- 같은 내용의 파일을 반복 업로드해도 중복 문서나 chunk가 생기지 않는다.
- 문서를 수정하면 새 버전의 모든 chunk가 준비된 뒤 검색 대상이 전환된다.
- 수정 도중 실패하면 이전 정상 버전은 계속 검색할 수 있다.
- 문서를 삭제하면 문서 메타데이터와 해당 문서의 모든 vector chunk가 함께 제거된다.
- 재색인은 문서 내용은 유지하고 embedding 또는 chunk profile만 새로 생성한다.
- 애플리케이션, PostgreSQL, Prometheus를 `docker compose up`으로 실행할 수 있다.
- 업로드·검색·LLM의 처리 시간, 성공/실패 횟수와 현재 문서 수를 지표로 확인할 수 있다.
- DB 단절, LLM 지연·오류를 테스트하고 HTTP 상태와 로그·지표가 의도대로 남는다.

### 1. 먼저 설계할 문서와 버전 모델

현재 `vector_store`의 각 행은 chunk이므로 파일 하나의 상태나 버전을 직접 관리하기
어렵습니다. 다음 두 테이블을 별도로 두고 vector metadata가 이 식별자를 참조하게 합니다.

```text
rag_document
  id(UUID), title, category, source_name
  current_version, status, created_at, updated_at

rag_document_version
  id(UUID), document_id, version, content_sha256
  chunk_size, overlap, embedding_model
  status(INGESTING, ACTIVE, FAILED, DELETED), created_at

vector_store.metadata
  document_id, version_id, version, content_sha256
  chunk_index, profile, active
```

반드시 정할 정책은 다음과 같습니다.

1. **논리 문서 ID**는 파일명이 바뀌어도 유지하고, 내용 변경은 새 `version`으로 기록합니다.
2. `content_sha256 + chunk profile + embedding model`이 같으면 동일 색인으로 판단합니다.
3. 같은 문서·같은 hash의 재업로드는 `200 unchanged`처럼 멱등하게 처리합니다.
4. 같은 내용이라도 논리 문서가 다르면 허용할지 정책을 명시합니다. 이 실습에서는 저장
   비용과 검색 중복을 막기 위해 `content hash + profile + embedding model`이 같은 ACTIVE
   버전을 전역 중복으로 판단하고 기존 문서를 반환합니다.
5. 검색은 `ACTIVE`인 현재 버전만 대상으로 하며 `INGESTING`과 `FAILED`는 제외합니다.

DB에서는 `(document_id, version)`, `(document_id, content_sha256, profile,
embedding_model)`에 unique constraint를 두고, JPA의 `@Version` 또는 조건부 update로
동시 수정 충돌을 감지합니다. 애플리케이션의 사전 조회만으로 중복을 막을 수 없는 이유는
두 요청이 동시에 조회를 통과할 수 있기 때문입니다.

### 2. 구현할 API

| Method | Path | 역할 | 주요 응답 |
|---|---|---|---|
| `GET` | `/api/rag/documents` | 문서 목록과 현재 상태 조회 | 문서 ID, 현재 버전, 상태, chunk 수 |
| `GET` | `/api/rag/documents/{id}` | 문서와 버전 이력 조회 | 현재 버전과 과거 버전 |
| `PUT` | `/api/rag/documents/{id}` | 파일·제목·카테고리 수정 | 새 버전 또는 `unchanged` |
| `DELETE` | `/api/rag/documents/{id}` | 문서와 모든 chunk 삭제 | `204 No Content` |
| `POST` | `/api/rag/documents/{id}/reindex` | profile/model을 바꿔 재색인 | 생성된 버전과 chunk 수 |

기존 `POST /api/rag/documents` 응답에도 서버가 만든 `documentId`, `version`, `status`,
`contentSha256`을 추가합니다. 파일명이나 제목은 바뀔 수 있으므로 이후 수정·삭제 API의
식별자로 사용하지 않습니다.

### 3. 수정과 재색인의 안전한 처리 순서

Embedding은 외부 네트워크 호출이므로 DB transaction을 연 채 호출하지 않습니다. 다음과
같은 단계형 workflow로 구현합니다.

```text
요청 검증 및 hash 계산
  → INGESTING 버전 생성
  → 파일 읽기·chunk 생성·embedding 저장
  → 저장된 chunk 개수 검증
  → 짧은 transaction에서 새 버전을 ACTIVE로 전환
  → 이전 버전 비활성화 또는 삭제
```

Embedding 저장에 실패하면 새 버전을 `FAILED`로 표시하고 새로 저장된 chunk만
`version_id`로 정리합니다. 현재 `ACTIVE` 버전은 건드리지 않습니다. 이 방식은 DB와 외부
LLM을 하나의 ACID transaction으로 묶을 수 없을 때 사용하는 보상 작업(compensating
action)과 상태 머신을 연습하게 합니다.

삭제는 먼저 문서를 `DELETING` 상태로 바꿔 신규 검색·수정과 충돌하지 않게 한 뒤,
`document_id`로 vector를 삭제하고 문서 레코드를 정리합니다. 물리 삭제가 실패하면 상태를
남겨 재시도할 수 있어야 합니다. 감사 이력이 중요하다면 문서 레코드는 soft delete하고
chunk만 물리 삭제하는 정책도 비교해 봅니다.

### 4. 단계별 구현 순서와 테스트

#### 단계 A — 문서 카탈로그와 중복 방지

- Flyway migration으로 문서·버전 테이블과 unique constraint를 만듭니다.
- 기존 적재 서비스가 `documentId`, `versionId`를 metadata에 기록하게 바꿉니다.
- 동일 파일 재업로드, 같은 이름의 다른 내용, 다른 이름의 같은 내용을 각각 테스트합니다.
- 동시에 동일 파일을 두 번 업로드해도 버전이 하나만 생기는 integration test를 작성합니다.

#### 단계 B — 수정·삭제·재색인

- 수정 성공, 내용이 같은 수정, 존재하지 않는 문서, 이미 삭제 중인 문서를 구분합니다.
- 문서 하나에 여러 page와 chunk가 있을 때 전부 삭제되는지 DB에서 검증합니다.
- 재색인 후 검색 결과에 새 `version`만 나타나는지 확인합니다.
- embedding 도중 예외를 발생시켜 이전 버전이 계속 검색되는지 확인합니다.

#### 단계 C — Docker Compose 통합

현재 Compose의 pgvector에 다음 서비스를 점진적으로 추가합니다.

```text
app → pgvector
  └→ OpenAI API
app → /actuator/prometheus ← prometheus ← grafana(선택)
```

- 애플리케이션용 `Dockerfile`을 multi-stage build로 작성합니다.
- DB URL은 `localhost`가 아니라 Compose service 이름 `pgvector`를 사용합니다.
- API key는 image나 Git에 넣지 않고 `${OPENAI_API_KEY}`로 주입합니다.
- pgvector에는 `pg_isready`, app에는 Actuator health endpoint healthcheck를 둡니다.
- app은 DB가 healthy가 된 뒤 시작하되, 시작 순서만으로 장애 복구를 보장한다고 생각하지
  말고 애플리케이션의 timeout과 재시도도 유지합니다.

#### 단계 D — Actuator·Micrometer 지표

의존성에 Actuator와 Prometheus registry를 추가하고 필요한 endpoint만 노출합니다.

```gradle
implementation 'org.springframework.boot:spring-boot-starter-actuator'
runtimeOnly 'io.micrometer:micrometer-registry-prometheus'
```

우선 다음 custom metric을 구현합니다.

| Metric | Type | 확인할 내용 |
|---|---|---|
| `rag.ingestion.duration` | Timer | 업로드·수정·재색인의 p50/p95 |
| `rag.ingestion.operations` | Counter | operation/status별 성공·실패 수 |
| `rag.search.duration` | Timer | mode별 검색 지연시간 |
| `rag.search.results` | DistributionSummary | 검색 결과 개수 분포 |
| `rag.llm.duration` | Timer | 모델·결과별 응답시간 |
| `rag.llm.errors` | Counter | timeout, 429, 5xx, invalid-response 구분 |
| `rag.documents.active` | Gauge | 현재 활성 문서 수 |

`documentId`, 질문 원문, 사용자 ID처럼 값의 종류가 계속 늘어나는 데이터는 metric tag로
넣지 않습니다. 이는 시계열 수가 폭증하는 high cardinality 문제를 만듭니다. 상세 식별자는
구조화 로그에 기록하고 metric tag는 `operation`, `mode`, `outcome`, `error.type`처럼
값의 종류가 제한된 항목만 사용합니다.

#### 단계 E — DB·LLM 장애 테스트

| 장애 | 주입 방법 | 기대 결과 |
|---|---|---|
| DB 연결 거부 | test DB 중지 또는 Toxiproxy 연결 차단 | 빠른 실패, 민감정보 없는 503, DB 오류 metric |
| DB 응답 지연 | Toxiproxy latency/toxic | query timeout 적용, worker 고갈 없음 |
| LLM 429/500 | MockWebServer 또는 WireMock | 설정한 transient 오류만 제한 재시도 |
| LLM 장시간 무응답 | 응답 delay | 지정 시간 뒤 504, timeout metric 증가 |
| LLM 잘못된 응답 | 빈 본문·인용 없는 응답 stub | 502와 validation 오류 기록 |
| 색인 중 LLM 실패 | N번째 embedding 요청 실패 | 새 버전 FAILED, 이전 버전 ACTIVE |

재시도 횟수만 확인하지 말고 **전체 소요 시간**도 assertion합니다. 각 시도 timeout이 너무
길면 3회 재시도가 사용자 요청 시간을 크게 늘릴 수 있습니다. DB write처럼 중복 부작용이
있는 작업은 멱등성이 확인된 경우에만 자동 재시도합니다.

### 5. 이번 실습에서 학습해야 할 것

1. **Aggregate와 식별자**: 파일, 논리 문서, 문서 버전, chunk ID가 왜 서로 다른지
   설명하고 각 API와 평가 데이터가 어떤 ID를 사용해야 하는지 구분합니다.
2. **멱등성과 동시성**: hash 사전 검사, DB unique constraint, optimistic locking의 역할이
   서로 다르며 모두 필요한 상황을 익힙니다.
3. **정합성과 보상 transaction**: DB와 외부 embedding API를 단일 transaction으로
   묶지 않고 상태 전이와 cleanup으로 복구하는 방법을 익힙니다.
4. **무중단 재색인**: 새 색인이 완성되기 전에는 이전 색인을 검색하고, 완성 뒤 한 번에
   활성 버전을 전환하는 이유를 익힙니다.
5. **관측 가능성**: health는 현재 생존 여부, metric은 추세와 경보, log는 개별 원인 추적,
   trace는 서비스 간 경로 분석이라는 차이를 익힙니다.
6. **SLI와 percentile**: 평균 지연시간만 보지 않고 p95/p99, 오류율, timeout 비율을 함께
   보는 이유와 이를 바탕으로 SLO를 정하는 방법을 익힙니다.
7. **재시도와 timeout**: 모든 오류를 재시도하지 않으며, exponential backoff와 jitter,
   총 시간 예산, 멱등성을 함께 설계해야 함을 익힙니다.
8. **장애 주입 테스트**: 정상 응답을 mock하는 단위 테스트를 넘어 실제 연결 단절·지연·부분
   실패에서 상태와 데이터가 어떻게 남는지 검증합니다.
9. **설정과 secret 관리**: local/compose/test profile을 분리하고 API key를 환경 변수나
   secret으로 전달하며 로그와 actuator endpoint에 노출하지 않습니다.
10. **운영 문서화**: 실행법뿐 아니라 migration, health 확인, metric 조회, 실패한 재색인
    복구, 데이터 초기화 절차까지 README에 남기는 습관을 익힙니다.

### 6. 권장 제출 결과물

- 문서·버전 ERD와 `ACTIVE/INGESTING/FAILED/DELETING` 상태 전이도
- API별 정상·중복·동시 요청·부분 실패 integration test
- `docker compose up --build` 한 번으로 실행되는 구성
- Actuator health와 Prometheus metric 예시
- 장애별 “주입 방법 → 관찰된 HTTP 응답 → DB 최종 상태 → metric 변화” 표
- 기존 RAG 평가를 재실행한 결과와 재색인 전후 Recall/MRR 회귀 여부

구현은 한 번에 전부 진행하기보다 **단계 A의 데이터 모델과 중복 테스트를 먼저 완성**한
뒤 다음 단계로 넘어갑니다. 이 기반 없이 CRUD부터 추가하면 파일명, hash, chunk 중 무엇을
문서 ID로 볼지가 섞여 수정과 삭제에서 데이터 정합성 문제가 커집니다.
