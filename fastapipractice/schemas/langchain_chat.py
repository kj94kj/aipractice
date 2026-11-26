from pydantic import BaseModel, Field


class ChatRequest(BaseModel):

    question: str = Field(min_length=1)


class ChatResponse(BaseModel):

    answer: str

class AnalysisResponse(BaseModel):

    category: str = Field(
        description="입력 문서의 주제"
    )

    summary: str = Field(
        description="문서의 핵심 내용 요약"
    )

    importance: int = Field(
        ge=1,
        le=5,
        description="중요도"
    )

    keywords: list[str] = Field(
        description="주요 키워드"
    )