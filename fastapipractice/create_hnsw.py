from langchain_postgres.v2.indexes import HNSWIndex

from services.rag.vector_store import get_vector_store


store = get_vector_store()

index = HNSWIndex(
    name="rag_documents_embedding_hnsw",
    m=16,
    ef_construction=64,
)

store.apply_vector_index(index)
print("HNSW 인덱스 생성 완료")