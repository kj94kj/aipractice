from contextlib import asynccontextmanager

from fastapi import FastAPI

from database import Base, engine
from routers.todo import router
from routers.external import router as external_router
from routers.llm import router as llm_router
from routers.langchain_chat import router as langchain_router


@asynccontextmanager
async def lifespan(app: FastAPI):
    Base.metadata.create_all(bind=engine)

    yield

    engine.dispose()


app = FastAPI(lifespan=lifespan)

app.include_router(router) 
app.include_router(external_router)
app.include_router(llm_router)
app.include_router(langchain_router)