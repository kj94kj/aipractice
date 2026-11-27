from langchain_classic.retrievers import EnsembleRetriever

from services.rag.vector_store import get_vector_store
from services.rag.bm25_retriever import bm25_retriever


# 벡터 검색기
vector_store = get_vector_store()

vector_retriever = vector_store.as_retriever(
    search_type="similarity",
    search_kwargs={"k": 10}
)


# 하이브리드 검색기
hybrid_retriever = EnsembleRetriever(
    retrievers=[
        vector_retriever,
        bm25_retriever
    ],
    weights=[0.5, 0.5],
    c=60
)

# Similarity Threshold
threshold_retriever = vector_store.as_retriever(
    search_type="similarity_score_threshold",
    search_kwargs={
        "k": 10,
        "score_threshold": 0.4
    }
)

# MMR 검색
mmr_retriever = vector_store.as_retriever(
    search_type="mmr",
    search_kwargs={
        "k": 10,
        "fetch_k": 20,
        "lambda_mult": 0.5
    }
)

# 검색 방식 선택
def retrieve_documents(
    question: str,
    mode: str = "hybrid",
    top_k: int = 4
):

    retrievers = {
        "vector": vector_retriever,
        "keyword": bm25_retriever,
        "hybrid": hybrid_retriever,
        "mmr": mmr_retriever,
        "threshold": threshold_retriever,
    }

    if mode not in retrievers:
        raise ValueError("지원하지 않는 검색 방식입니다.")

    return retrievers[mode].invoke(question)[:top_k]