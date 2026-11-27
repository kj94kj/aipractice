import os

from dotenv import load_dotenv
from langchain_openai import OpenAIEmbeddings
from langchain_postgres import PGEngine, PGVectorStore

load_dotenv()

embeddings = OpenAIEmbeddings(
    model="text-embedding-3-small"
)

engine = PGEngine.from_connection_string(
    url=os.environ["RAG_DATABASE_URL"]
)

TABLE_NAME = "rag_documents"


def init_db():

    engine.init_vectorstore_table(
        table_name=TABLE_NAME,
        vector_size=1536
    )


def get_vector_store():

    return PGVectorStore.create_sync(
        engine=engine,
        table_name=TABLE_NAME,
        embedding_service=embeddings
    )


if __name__ == "__main__":
    init_db()