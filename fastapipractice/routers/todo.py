from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import select
from sqlalchemy.orm import Session

from database import get_db
from models.todo import Todo
from schemas.todo import TodoRequest, TodoResponse

router = APIRouter(
    prefix="/todos",
    tags=["Todo"]
)

DbSession = Annotated[Session, Depends(get_db)]

@router.post(
    "",
    response_model=TodoResponse,
    status_code=201
)
def create_todo(request: TodoRequest, db: DbSession):

    todo = Todo(
        title=request.title,
        completed=request.completed
    )

    db.add(todo)
    db.commit()
    db.refresh(todo)

    return todo

@router.get("", response_model=list[TodoResponse])
def get_todos(db: DbSession):

    stmt = select(Todo)

    return db.scalars(stmt).all()

@router.get("/{todo_id}", response_model=TodoResponse)
def get_todo(todo_id: int, db: DbSession):

    todo = db.get(Todo, todo_id)

    if todo is None:
        raise HTTPException(
            status_code=404,
            detail="Todo not found"
        )

    return todo

@router.put("/{todo_id}", response_model=TodoResponse)
def update_todo(
    todo_id: int,
    request: TodoRequest,
    db: DbSession
):

    todo = db.get(Todo, todo_id)

    if todo is None:
        raise HTTPException(
            status_code=404,
            detail="Todo not found"
        )

    todo.title = request.title
    todo.completed = request.completed

    db.commit()
    db.refresh(todo)

    return todo

@router.delete("/{todo_id}", status_code=204)
def delete_todo(todo_id: int, db: DbSession):

    todo = db.get(Todo, todo_id)

    if todo is None:
        raise HTTPException(
            status_code=404,
            detail="Todo not found"
        )

    db.delete(todo)
    db.commit()