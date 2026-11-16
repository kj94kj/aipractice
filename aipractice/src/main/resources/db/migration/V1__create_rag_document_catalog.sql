CREATE TABLE rag_document (
    id UUID PRIMARY KEY,
    title VARCHAR(255) NOT NULL,
    category VARCHAR(100) NOT NULL,
    source_name VARCHAR(500) NOT NULL,
    current_version INTEGER,
    status VARCHAR(30) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE rag_document_version (
    id UUID PRIMARY KEY,
    document_id UUID NOT NULL REFERENCES rag_document(id) ON DELETE CASCADE,
    version INTEGER NOT NULL,
    content_sha256 CHAR(64) NOT NULL,
    content BYTEA NOT NULL,
    chunk_size INTEGER NOT NULL,
    overlap_size INTEGER NOT NULL,
    profile VARCHAR(80) NOT NULL,
    embedding_model VARCHAR(120) NOT NULL,
    status VARCHAR(30) NOT NULL,
    chunk_count INTEGER NOT NULL DEFAULT 0,
    error_message VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    activated_at TIMESTAMPTZ,
    CONSTRAINT uq_rag_document_version UNIQUE (document_id, version)
);

CREATE INDEX ix_rag_document_status ON rag_document(status);
CREATE INDEX ix_rag_document_version_document ON rag_document_version(document_id);
CREATE INDEX ix_rag_document_version_hash ON rag_document_version(content_sha256, profile, embedding_model);
CREATE UNIQUE INDEX uq_rag_active_fingerprint
    ON rag_document_version(content_sha256, profile, embedding_model)
    WHERE status = 'ACTIVE';
