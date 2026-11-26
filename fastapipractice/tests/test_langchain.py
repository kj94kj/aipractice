from unittest.mock import AsyncMock, patch

from fastapi.testclient import TestClient

from main import app

from langchain_core.language_models.fake_chat_models import (
    FakeListChatModel
)

from langchain_core.output_parsers import StrOutputParser

from services.langchain_service import prompt

client = TestClient(app)


def test_langchain_chat():

    with patch(
        "routers.langchain_chat.generate_answer",
        new_callable=AsyncMock
    ) as mock_llm:

        mock_llm.return_value = "테스트 응답입니다."

        response = client.post(
            "/langchain/chat",
            json={
                "question": "FastAPI란?"
            }
        )

        assert response.status_code == 200

        assert response.json() == {
            "answer": "테스트 응답입니다."
        }

        mock_llm.assert_awaited_once_with(
            "FastAPI란?"
        )

def test_prompt():

    messages = prompt.format_messages(
        question="Redis란?"
    )

    assert messages[-1].content == "Redis란?"

    assert messages[0].type == "system"


def test_chain():

    fake_model = FakeListChatModel(
        responses=["Redis는 인메모리 데이터 저장소입니다."]
    )

    test_chain = (
        prompt
        | fake_model
        | StrOutputParser()
    )

    result = test_chain.invoke({
        "question": "Redis란?"
    })

    assert isinstance(result, str)

    assert result == (
        "Redis는 인메모리 데이터 저장소입니다."
    )