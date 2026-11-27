import json

from fastapi import APIRouter
from pathlib import Path
from tempfile import TemporaryDirectory

from schemas.rag import RagRequest
from fastapi import UploadFile, HTTPException

from services.rag.generation import ask_async, stream_answer
from services.rag.retrieval import retrieve_documents
from services.rag.ingestion import ingest_document
from fastapi.responses import StreamingResponse


router = APIRouter(
    prefix="/rag",
    tags=["RAG"]
)


@router.post("/search")
def search(request: RagRequest):
    return retrieve_documents(
        request.question,
        request.mode,
        request.top_k,
    )


@router.post("/chat")
async def chat(request: RagRequest):
    return await ask_async(
        request.question,
        request.history,
        request.mode,
        request.top_k,
    )

@router.post("/chat/stream")
async def chat_stream(request: RagRequest):
    async def event_generator():
        async for item in stream_answer(
            request.question,
            request.history,
            request.mode,
            request.top_k,
        ):
            data = json.dumps(item["data"], ensure_ascii=False)
            yield f"event: {item['event']}\ndata: {data}\n\n"

        yield "event: done\ndata: {}\n\n"

    return StreamingResponse(
        event_generator(),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "X-Accel-Buffering": "no",
        },
    )

@router.post("/documents")
def upload_document(file: UploadFile):

    filename = Path(file.filename or "").name

    if Path(filename).suffix.lower() not in [".pdf", ".txt"]:
        raise HTTPException(
            status_code=400,
            detail="PDF와 TXT만 지원합니다."
        )

    with TemporaryDirectory() as directory:

        path = Path(directory) / filename

        with path.open("wb") as buffer:
            buffer.write(file.file.read())

        chunks = ingest_document(str(path))

    return {
        "filename": filename,
        "chunk_count": len(chunks)
    }