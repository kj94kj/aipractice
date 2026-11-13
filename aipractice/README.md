# AI Practice - 1주차 RAG 기본 파이프라인

텍스트/PDF 문서를 Chunk로 분할하고 OpenAI Embedding을 생성하여 pgvector에 저장한 뒤,
질문과 유사한 문서를 검색해 답변하는 학습용 프로젝트입니다.

## RAG 흐름

```text
문서 → DocumentReader → TokenTextSplitter → Embedding → pgvector
질문 → Embedding → Top-K 유사도 검색 → Prompt Context → LLM 답변
```

`POST /api/rag/ask`는 일반 LLM 답변과 RAG 답변을 함께 반환합니다. `sources`에서 실제로
검색된 파일, 페이지, 유사도 점수, Chunk 일부를 확인할 수 있습니다.

## 준비 사항

- Java 17
- Docker Desktop
- OpenAI API Key

PowerShell에서 API Key를 설정합니다.

```powershell
$env:OPENAI_API_KEY="본인의 API Key"
```

## 실행

pgvector를 먼저 시작합니다.

```powershell
docker compose up -d
docker compose ps
```

애플리케이션을 실행합니다.

```powershell
.\gradlew.bat bootRun
```

## 1. 문서 업로드

프로젝트에 포함된 샘플 문서를 업로드합니다.

```powershell
curl.exe -X POST `
  -F "file=@src/main/resources/sample-documents/company-policy.txt" `
  http://localhost:8080/api/rag/documents
```

예상 응답:

```json
{
  "filename": "company-policy.txt",
  "originalDocumentCount": 1,
  "chunkCount": 1
}
```

PDF도 같은 API로 업로드할 수 있습니다.

```powershell
curl.exe -X POST `
  -F "file=@C:/문서/sample.pdf" `
  http://localhost:8080/api/rag/documents
```

이미지만 있는 스캔 PDF는 OCR이 없으므로 텍스트를 추출하지 못합니다.

## 2. 문서 기반 질문

```powershell
curl.exe -X POST `
  -H "Content-Type: application/json; charset=UTF-8" `
  -d '{"question":"남은 연차는 몇 일까지 이월할 수 있어?","topK":3}' `
  http://localhost:8080/api/rag/ask
```

응답 형태:

```json
{
  "question": "남은 연차는 몇 일까지 이월할 수 있어?",
  "generalAnswer": "회사 정책에 따라 다릅니다.",
  "ragAnswer": "제공된 문서에 따르면 최대 5일까지 이월할 수 있습니다.",
  "sources": [
    {
      "documentId": "...",
      "source": "company-policy.txt",
      "page": "-",
      "score": 0.8,
      "excerpt": "에이아이프랙티스 회사 정책..."
    }
  ]
}
```

`/api/rag/ask` 한 번은 질문 Embedding 1회와 비교를 위한 Chat 요청 2회를 사용합니다.

## 3. pgvector 저장 결과 확인

```powershell
docker compose exec pgvector `
  psql -U postgres -d ragdb `
  -c "select id, left(content, 80), metadata from vector_store limit 10;"
```

## 테스트

```powershell
.\gradlew.bat test
```

## 학습할 코드

- `DocumentIngestionService`: TXT/PDF 읽기, Chunk 분할, VectorStore 저장
- `RagService`: Top-K 검색, Context 생성, 일반/RAG 답변 비교
- `RagController`: 문서 업로드 및 질문 REST API

## 현재 범위와 다음 과제

현재는 같은 파일을 다시 업로드하면 Chunk가 중복 저장됩니다. 다음 단계에서 다음 기능을
추가해 볼 수 있습니다.

1. 파일 SHA-256을 metadata에 저장하고 중복 업로드 방지
2. 문서별 삭제 API 구현
3. `topK`와 `similarityThreshold` 결과 비교
4. Chunk 크기를 200/500/1000으로 바꾸고 검색 품질 비교
5. 답변에 `[문서 1]` 형태의 인용 표시 추가
