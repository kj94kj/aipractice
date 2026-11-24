from contextlib import asynccontextmanager

from fastapi import FastAPI

from database import Base, engine
from routers.todo import router


@asynccontextmanager
async def lifespan(app: FastAPI):
    Base.metadata.create_all(bind=engine)

    yield

    engine.dispose()


app = FastAPI(lifespan=lifespan)

app.include_router(router) 