from unittest.mock import AsyncMock, patch

from fastapi.testclient import TestClient

from main import app

client = TestClient(app)


def test_chat():

    with patch(
        "routers.llm.generate_answer",
        new_callable=AsyncMock
    ) as mock_llm:

        mock_llm.return_value = "FastAPI는 웹 프레임워크입니다."

        response = client.post(
            "/llm/chat",
            json={"question": "FastAPI란?"}
        )

        assert response.status_code == 200

        assert response.json()["answer"] == (
            "FastAPI는 웹 프레임워크입니다."
        )

        mock_llm.assert_awaited_once_with(
            "FastAPI란?"
        )

async def fake_stream(question):

    yield "안녕하세요."
    yield " FastAPI입니다."


def test_chat_stream():

    with patch(
        "routers.llm.stream_answer",
        side_effect=fake_stream
    ):

        with client.stream(
            "POST",
            "/llm/chat/stream",
            json={"question": "안녕"}
        ) as response:

            assert response.status_code == 200

            assert "text/event-stream" in (
                response.headers["content-type"]
            )

            lines = list(response.iter_lines())

            assert (
                'data: {"delta": "안녕하세요."}'
                in lines
            )

            assert (
                'data: {"delta": " FastAPI입니다."}'
                in lines
            )

            assert "event: done" in lines