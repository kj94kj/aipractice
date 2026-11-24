import asyncio
import json

from fastapi import APIRouter
from fastapi.responses import StreamingResponse
from fastapi import HTTPException

from schemas.llm import ChatRequest, ChatResponse
from services.llm import generate_answer
from services.llm import stream_answer
from openai import (
    APITimeoutError,
    APIConnectionError,
    RateLimitError,
    APIStatusError
)

router = APIRouter(
    prefix="/llm",
    tags=["LLM"]
)


@router.post("/chat", response_model=ChatResponse)
async def chat(request: ChatRequest):

    try:
        answer = await generate_answer(
            request.question
        )

        return ChatResponse(answer=answer)

    except APITimeoutError:
        raise HTTPException(
            status_code=504,
            detail="LLM API timeout"
        )

    except RateLimitError:
        raise HTTPException(
            status_code=429,
            detail="LLM API rate limit exceeded"
        )

    except APIConnectionError:
        raise HTTPException(
            status_code=502,
            detail="LLM API connection failed"
        )

    except APIStatusError:
        raise HTTPException(
            status_code=502,
            detail="LLM API error"
        )

async def fake_stream():

    words = [
        "안녕하세요. ",
        "FastAPI의 ",
        "스트리밍 응답을 ",
        "테스트하고 있습니다."
    ]

    for word in words:

        yield word

        await asyncio.sleep(1)

@router.get("/stream-test")
async def stream_test():

    return StreamingResponse(
        fake_stream(),
        media_type="text/plain"
    )

@router.post("/chat/stream")
async def chat_stream(request: ChatRequest):

    async def event_generator():

        try:
            async for text in stream_answer(
                request.question
            ):

                data = json.dumps(
                    {"delta": text},
                    ensure_ascii=False
                )

                yield f"data: {data}\n\n"

            yield "event: done\ndata: {}\n\n"

        except Exception:

            yield (
                'event: error\n'
                'data: {"message":"LLM generation failed"}\n\n'
            )

    return StreamingResponse(
        event_generator(),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "X-Accel-Buffering": "no"
        }
    )
