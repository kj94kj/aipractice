from fastapi import FastAPI

app = FastAPI()

@app.get("/")
def home():
    return {"message": "Hello FastAPI!"}

@app.get("/users/{user_id}")
def get_user(user_id: int):
    return {"userId": user_id}