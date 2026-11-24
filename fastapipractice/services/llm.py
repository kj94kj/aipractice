import os

from dotenv import load_dotenv
from openai import AsyncOpenAI

load_dotenv()


async def generate_answer(question: str) -> str:

    async with AsyncOpenAI(timeout=60.0) as client:

        response = await client.responses.create(
            model=os.getenv("OPENAI_MODEL", "gpt-4.0-mini"),
            instructions="질문에 한국어로 이해하기 쉽게 답변하세요.",
            input=question,
            max_output_tokens=1000
        )

        return response.output_text

async def stream_answer(question: str):

    async with AsyncOpenAI(timeout=60.0) as client:

        stream = await client.responses.create(
            model=os.getenv("OPENAI_MODEL", "gpt-4.0-mini"),
            instructions="질문에 한국어로 이해하기 쉽게 답변하세요.",
            input=question,
            max_output_tokens=1000,
            stream=True
        )

        async with stream:

            async for event in stream:

                if event.type == "response.output_text.delta":
                    yield event.delta

                elif event.type == "response.completed":
                    return

                elif event.type in (
                    "response.failed",
                    "response.incomplete",
                    "error"
                ):
                    raise RuntimeError("LLM generation failed")

            raise RuntimeError("LLM stream ended unexpectedly")