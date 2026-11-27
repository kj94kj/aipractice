import asyncio

from langchain_core.output_parsers import StrOutputParser
from langchain_core.prompts import ChatPromptTemplate
from langchain_openai import ChatOpenAI

from schemas.rag import RagAnswer, RagTurn
from services.rag.retrieval import retrieve_documents


llm = ChatOpenAI(
    model="gpt-4o-mini",
    temperature=0,
)


rewrite_prompt = ChatPromptTemplate.from_messages([
    (
        "system",
        "이전 대화와 현재 질문을 보고, 문서 검색에 사용할 "
        "독립적인 질문 한 문장만 작성하세요. "
        "대화 기록은 대명사나 생략된 주어를 해석하는 데만 사용하세요."
    ),
    (
        "human",
        "이전 대화:\n{history}\n\n현재 질문:\n{question}"
    ),
])

rewrite_chain = rewrite_prompt | llm | StrOutputParser()


answer_prompt = ChatPromptTemplate.from_messages([
    (
        "system",
        "당신은 사내 규정에 답변하는 AI입니다.\n"
        "오직 아래 참고 문서만 사실의 근거로 사용하세요.\n"
        "이전 대화는 질문을 이해하는 데만 사용하고, 사실의 근거로 사용하지 마세요.\n"
        "근거가 없으면 확인할 수 없다고 답하세요.\n"
        "인용 번호는 참고 문서의 [문서 N] 번호를 사용하세요.\n\n"
        "이전 대화:\n{history}\n\n"
        "참고 문서:\n{context}"
    ),
    ("human", "{question}"),
])

structured_chain = answer_prompt | llm.with_structured_output(RagAnswer, method="json_schema")

text_chain = answer_prompt | llm | StrOutputParser()


def format_documents(documents):
    return "\n\n".join(
        f"[문서 {i}] 출처: {doc.metadata.get('source', '알 수 없음')}\n"
        f"{doc.page_content}"
        for i, doc in enumerate(documents, start=1)
    )


def format_history(history: list[RagTurn]) -> str:
    if not history:
        return "없음"

    return "\n".join(
        f"사용자: {turn.question}\nAI: {turn.answer}"
        for turn in history[-6:]
    )


def make_sources(documents):
    return [
        {
            "number": i,
            "metadata": doc.metadata,
            "content": doc.page_content,
        }
        for i, doc in enumerate(documents, start=1)
    ]


async def prepare_documents(
    question: str,
    history: list[RagTurn],
    mode: str,
    top_k: int,
):
    history_text = format_history(history)
    search_question = question

    # 후속 질문일 때만 검색어를 재작성합니다.
    if history:
        rewritten = await rewrite_chain.ainvoke({
            "history": history_text,
            "question": question,
        })
        search_question = rewritten.strip() or question

    # 현재 retrieve_documents()는 동기 함수이므로 스레드에서 실행합니다.
    documents = await asyncio.to_thread(
        retrieve_documents,
        search_question,
        mode,
        top_k,
    )
    return documents, history_text


async def ask_async(
    question: str,
    history: list[RagTurn],
    mode: str,
    top_k: int,
):
    documents, history_text = await prepare_documents(
        question, history, mode, top_k
    )

    if not documents:
        return {
            "answer": "제공된 문서에서 답변을 확인할 수 없습니다.",
            "answerable": False,
            "citations": [],
            "sources": [],
        }

    result: RagAnswer = await structured_chain.ainvoke({
        "history": history_text,
        "context": format_documents(documents),
        "question": question,
    })

    # 모델이 존재하지 않는 문서 번호를 반환했을 때 제거합니다.
    citations = [
        number
        for number in result.citations
        if 1 <= number <= len(documents)
    ]
    if not result.answerable:
        citations = []

    return {
        "answer": result.answer,
        "answerable": result.answerable,
        "citations": citations,
        "sources": make_sources(documents),
    }


async def stream_answer(
    question: str,
    history: list[RagTurn],
    mode: str,
    top_k: int,
):
    documents, history_text = await prepare_documents(
        question, history, mode, top_k
    )

    yield {
        "event": "sources",
        "data": make_sources(documents),
    }

    if not documents:
        yield {
            "event": "delta",
            "data": {
                "content": "제공된 문서에서 답변을 확인할 수 없습니다."
            },
        }
        return

    async for chunk in text_chain.astream({
        "history": history_text,
        "context": format_documents(documents),
        "question": question,
    }):
        yield {
            "event": "delta",
            "data": {"content": chunk},
        }