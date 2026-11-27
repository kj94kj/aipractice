from unittest.mock import AsyncMock, patch

from fastapi import FastAPI
from fastapi.testclient import TestClient

from routers import rag


app = FastAPI()
app.include_router(rag.router)
client = TestClient(app)


def test_rag_chat():
    expected = {
        "answer": "주 2회 가능합니다.",
        "answerable": True,
        "citations": [1],
        "sources": [],
    }

    with patch(
        "routers.rag.ask_async",
        new_callable=AsyncMock,
    ) as mock_ask:
        mock_ask.return_value = expected

        response = client.post(
            "/rag/chat",
            json={"question": "재택근무는 주 몇 회 가능한가요?"},
        )

    assert response.status_code == 200
    assert response.json() == expected
    mock_ask.assert_awaited_once()


async def fake_stream(*args, **kwargs):
    yield {
        "event": "delta",
        "data": {"content": "주 2회"},
    }


def test_rag_stream():
    with patch(
        "routers.rag.stream_answer",
        side_effect=fake_stream,
    ):
        response = client.post(
            "/rag/chat/stream",
            json={"question": "재택근무는 주 몇 회 가능한가요?"},
        )

    assert response.status_code == 200
    assert "text/event-stream" in response.headers["content-type"]
    assert "event: delta" in response.text
    assert "주 2회" in response.text
    assert "event: done" in response.text