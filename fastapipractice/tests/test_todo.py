import pytest

from fastapi.testclient import TestClient
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker
from sqlalchemy.pool import StaticPool

from database import Base, get_db
from main import app


@pytest.fixture
def client():

    test_engine = create_engine(
        "sqlite://",
        connect_args={"check_same_thread": False},
        poolclass=StaticPool
    )

    TestingSessionLocal = sessionmaker(
        bind=test_engine
    )

    Base.metadata.create_all(bind=test_engine)

    def override_get_db():
        db = TestingSessionLocal()

        try:
            yield db
        finally:
            db.close()

    app.dependency_overrides[get_db] = override_get_db

    try:
        yield TestClient(app)
    finally:
        app.dependency_overrides.clear()

        Base.metadata.drop_all(bind=test_engine)

        test_engine.dispose()


def test_create_todo(client):

    response = client.post(
        "/todos",
        json={
            "title": "FastAPI 공부",
            "completed": False
        }
    )

    assert response.status_code == 201

    data = response.json()

    assert data["title"] == "FastAPI 공부"
    assert data["completed"] is False
    assert "id" in data

def test_get_todo(client):

    created = client.post(
        "/todos",
        json={"title": "공부"}
    ).json()

    todo_id = created["id"]

    response = client.get(f"/todos/{todo_id}")

    assert response.status_code == 200
    assert response.json()["title"] == "공부"

def test_update_todo(client):

    created = client.post(
        "/todos",
        json={"title": "공부"}
    ).json()

    todo_id = created["id"]

    response = client.put(
        f"/todos/{todo_id}",
        json={
            "title": "공부 완료",
            "completed": True
        }
    )

    assert response.status_code == 200
    assert response.json()["completed"] is True


def test_delete_todo(client):

    created = client.post(
        "/todos",
        json={"title": "공부"}
    ).json()

    todo_id = created["id"]

    response = client.delete(f"/todos/{todo_id}")

    assert response.status_code == 204

    response = client.get(f"/todos/{todo_id}")

    assert response.status_code == 404

def test_todo_not_found(client):

    response = client.get("/todos/99999")

    assert response.status_code == 404

def test_invalid_title(client):

    response = client.post(
        "/todos",
        json={"title": ""}
    )

    assert response.status_code == 422

