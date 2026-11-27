from pathlib import Path
from services.rag.ingestion import ingest_document

for path in Path("documents").glob("*.txt"):

    chunks = ingest_document(str(path))

    print(path.name, len(chunks))