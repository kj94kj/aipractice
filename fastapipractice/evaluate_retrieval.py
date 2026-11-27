from services.rag.retrieval import retrieve_documents


CASES = [
    ("신입사원 연차는 어떻게 발생하나요?", "leave.txt"),
    ("재택근무는 주 몇 회 가능한가요?", "remote.txt"),
    ("자기계발비는 연간 얼마까지 지원되나요?", "welfare.txt"),
]


def evaluate(mode: str, k: int = 4):
    hit_count = 0
    reciprocal_rank_sum = 0.0

    for question, expected_source in CASES:
        documents = retrieve_documents(question, mode=mode, top_k=k)
        rank = next(
            (
                i
                for i, doc in enumerate(documents, start=1)
                if doc.metadata.get("source") == expected_source
            ),
            None,
        )

        if rank is not None:
            hit_count += 1
            reciprocal_rank_sum += 1 / rank

        print(
            f"{mode:9} | 정답={expected_source:11} "
            f"| 순위={rank} | 질문={question}"
        )

    count = len(CASES)
    print(
        f"{mode:9} | Recall@{k}={hit_count / count:.3f} "
        f"| MRR@{k}={reciprocal_rank_sum / count:.3f}\n"
    )


if __name__ == "__main__":
    for search_mode in (
        "vector", "keyword", "hybrid", "mmr", "threshold"
    ):
        evaluate(search_mode)