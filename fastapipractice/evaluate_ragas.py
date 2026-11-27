import asyncio

from dotenv import load_dotenv
from openai import AsyncOpenAI
from ragas.embeddings.base import embedding_factory
from ragas.llms import llm_factory
from ragas.metrics.collections import AnswerRelevancy, Faithfulness

from services.rag.generation import ask_async


load_dotenv()


async def main():
    client = AsyncOpenAI()
    evaluator_llm = llm_factory("gpt-4o-mini", client=client)
    evaluator_embeddings = embedding_factory(
        "openai",
        model="text-embedding-3-small",
        client=client,
    )

    faithfulness = Faithfulness(llm=evaluator_llm)
    relevancy = AnswerRelevancy(
        llm=evaluator_llm,
        embeddings=evaluator_embeddings,
    )

    question = "재택근무는 주 몇 회 가능한가요?"
    result = await ask_async(question, [], "hybrid", 4)
    contexts = [source["content"] for source in result["sources"]]

    faith_score = await faithfulness.ascore(
        user_input=question,
        response=result["answer"],
        retrieved_contexts=contexts,
    )
    relevance_score = await relevancy.ascore(
        user_input=question,
        response=result["answer"],
    )

    print("답변:", result["answer"])
    print("Faithfulness:", faith_score.value)
    print("Answer Relevancy:", relevance_score.value)


if __name__ == "__main__":
    asyncio.run(main())