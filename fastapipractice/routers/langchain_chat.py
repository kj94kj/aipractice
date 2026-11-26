import json

from fastapi import APIRouter
from fastapi.responses import StreamingResponse

from schemas.langchain_chat import (
    ChatRequest,
    ChatResponse
)

from services.langchain_service import (
    generate_answer,
    stream_answer
)

from schemas.langchain_chat import AnalysisResponse
from services.langchain_service import analyze_text


router = APIRouter(
    prefix="/langchain",
    tags=["LangChain"]
)


@router.post(
    "/chat",
    response_model=ChatResponse
)
async def chat(request: ChatRequest):

    answer = await generate_answer(
        request.question
    )

    return ChatResponse(answer=answer)


@router.post("/chat/stream")
async def chat_stream(request: ChatRequest):

    async def event_generator():

        async for chunk in stream_answer(
            request.question
        ):

            data = json.dumps(
                {"content": chunk},
                ensure_ascii=False
            )

            yield f"data: {data}\n\n"

        yield "event: done\ndata: {}\n\n"

    return StreamingResponse(
        event_generator(),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "X-Accel-Buffering": "no"
        }
    )

@router.post(
    "/analyze",
    response_model=AnalysisResponse
)
async def analyze(request: ChatRequest):

    return await analyze_text(request.question)