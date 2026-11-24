from fastapi import APIRouter, Depends
from fastapi import Query

router = APIRouter()

def get_message():
    return "Hello FastAPI"


@router.get("/hello")
def hello(message: str = Depends(get_message)):
    return {"message": message} 

@router.get("/search")
def search(
    keyword: str = Query(min_length=2),
    page: int = Query(default=1, ge=1),
    size: int = Query(default=10, ge=1, le=100)
):
    return {
        "keyword": keyword,
        "page": page,
        "size": size
    }