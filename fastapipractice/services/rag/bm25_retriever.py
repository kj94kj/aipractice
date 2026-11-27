from pathlib import Path

from langchain_community.retrievers import BM25Retriever

from services.rag.ingestion import load_documents, splitter


def create_bm25_retriever():

    documents = []

    for path in Path("documents").glob("*.txt"):
        documents.extend(load_documents(str(path)))

    chunks = splitter.split_documents(documents)

    if not chunks:
        raise ValueError("검색할 문서가 없습니다.")

    for chunk in chunks:
        chunk.metadata["source"] = Path(
            chunk.metadata["source"]
        ).name

    retriever = BM25Retriever.from_documents(chunks)

    retriever.k = 10

    return retriever


bm25_retriever = create_bm25_retriever()


if __name__ == "__main__":

    docs = bm25_retriever.invoke("자기계발비")

    for doc in docs:
        print(doc.page_content)