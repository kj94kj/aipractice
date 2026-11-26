from langchain_openai import ChatOpenAI
from langchain_core.prompts import ChatPromptTemplate
from langchain_core.output_parsers import StrOutputParser
from schemas.langchain_chat import AnalysisResponse


model = ChatOpenAI(
    model="gpt-4o-mini",
    temperature=0,
    timeout=30,
    max_retries=2
)


prompt = ChatPromptTemplate.from_messages([
    (
        "system",
        "너는 프로그래밍 전문가이다. "
        "초보자가 이해할 수 있도록 설명해라."
    ),
    ("human", "{question}")
])


chain = prompt | model | StrOutputParser()


async def generate_answer(question: str) -> str:

    return await chain.ainvoke({
        "question": question
    })


async def stream_answer(question: str):

    async for chunk in chain.astream({
        "question": question
    }):

        yield chunk


structured_model = model.with_structured_output(
    AnalysisResponse,
    method="json_schema"
)


analysis_prompt = ChatPromptTemplate.from_messages([
    (
        "system",
        "입력된 문서를 분석하고 핵심 정보를 추출해라."
    ),
    ("human", "{text}")
])


analysis_chain = analysis_prompt | structured_model


async def analyze_text(text: str) -> AnalysisResponse:

    return await analysis_chain.ainvoke({
        "text": text
    })