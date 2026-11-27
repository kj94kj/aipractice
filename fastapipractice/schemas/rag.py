from typing import Literal

from pydantic import BaseModel, Field


class RagTurn(BaseModel):
    question: str
    answer: str


class RagRequest(BaseModel):
    question: str = Field(min_length=1)
    mode: Literal[
        "vector", "keyword", "hybrid", "mmr", "threshold"
    ] = "hybrid"
    top_k: int = Field(default=4, ge=1, le=10)
    history: list[RagTurn] = Field(default_factory=list)


class RagAnswer(BaseModel):
    answer: str = Field(description="문서를 근거로 생성한 답변")
    answerable: bool = Field(description="문서로 답변할 수 있는지 여부")
    citations: list[int] = Field(description="답변에 사용한 문서 번호")