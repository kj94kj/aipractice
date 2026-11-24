import httpx

from fastapi import APIRouter

router = APIRouter(
    prefix="/external",
    tags=["External"]
)


from fastapi import HTTPException


@router.get("/posts/{post_id}")
async def get_post(post_id: int):

    try:
        async with httpx.AsyncClient(timeout=10.0) as client:

            response = await client.get(
                f"https://jsonplaceholder.typicode.com/posts/{post_id}"
            )

            response.raise_for_status()

            return response.json()

    except httpx.TimeoutException:
        raise HTTPException(
            status_code=504,
            detail="External API timeout"
        )

    except httpx.HTTPStatusError:
        raise HTTPException(
            status_code=502,
            detail="External API error"
        )

    except httpx.RequestError:
        raise HTTPException(
            status_code=502,
            detail="External API connection failed"
        )

@router.post("/posts")
async def create_post():

    async with httpx.AsyncClient(timeout=10.0) as client:

        response = await client.post(
            "https://jsonplaceholder.typicode.com/posts",
            json={
                "title": "FastAPI",
                "body": "외부 API 호출 연습",
                "userId": 1
            }
        )

        response.raise_for_status()

        return response.json()

import asyncio


@router.get("/parallel")
async def get_multiple_posts():

    async with httpx.AsyncClient(timeout=10.0) as client:

        responses = await asyncio.gather(
            client.get(
                "https://jsonplaceholder.typicode.com/posts/1"
            ),
            client.get(
                "https://jsonplaceholder.typicode.com/posts/2"
            ),
            client.get(
                "https://jsonplaceholder.typicode.com/posts/3"
            )
        )

        for response in responses:
            response.raise_for_status()

        return [
            response.json()
            for response in responses
        ]