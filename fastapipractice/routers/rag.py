from fastapi import APIRouter
from pathlib import Path
from tempfile import TemporaryDirectory

from schemas.rag import RagRequest
from fastapi import UploadFile, HTTPException

from services.rag.generation import ask
from services.rag.retrieval import retrieve_documents
from services.rag.ingestion import ingest_document


router = APIRouter(
    prefix="/rag",
    tags=["RAG"]
)


@router.post("/search")
def search(request: RagRequest):

    return retrieve_documents(
        request.question
    )


@router.post("/chat")
def chat(request: RagRequest):

    return ask(request.question)

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