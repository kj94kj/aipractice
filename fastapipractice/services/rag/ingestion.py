from uuid import uuid4
from pathlib import Path

from langchain_community.document_loaders import (
    TextLoader,
    PyPDFLoader
)

from langchain_text_splitters import (
    RecursiveCharacterTextSplitter
)

from services.rag.vector_store import get_vector_store


splitter = RecursiveCharacterTextSplitter(
    chunk_size=500,
    chunk_overlap=50
)


def load_documents(file_path: str):

    if file_path.lower().endswith(".pdf"):
        loader = PyPDFLoader(file_path)

    elif file_path.lower().endswith(".txt"):
        loader = TextLoader(
            file_path,
            encoding="utf-8"
        )

    else:
        raise ValueError("지원하지 않는 파일 형식입니다.")

    return loader.load()


def ingest_document(file_path: str):

    documents = load_documents(file_path)

    chunks = splitter.split_documents(documents)

    for chunk in chunks:
        chunk.metadata["chunk_id"] = str(uuid4())
        chunk.metadata["source"] = Path(file_path).name

    ids = [
        chunk.metadata["chunk_id"]
        for chunk in chunks
    ]

    vector_store = get_vector_store()

    vector_store.add_documents(
        documents=chunks,
        ids=ids
    )

    return chunks