package com.example.aipractice.rag;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Repository
public class DocumentCatalogRepository {

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public DocumentCatalogRepository(
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public void createDocument(
            UUID id,
            String title,
            String category,
            String sourceName) {
        jdbcTemplate.update("""
                INSERT INTO rag_document(
                    id, title, category, source_name, status
                ) VALUES (?, ?, ?, ?, ?)
                """, id, title, category, sourceName, DocumentStatus.INGESTING.name());
    }

    public VersionRecord createVersion(
            UUID documentId,
            String contentHash,
            byte[] content,
            int chunkSize,
            int overlap,
            String profile,
            String embeddingModel) {
        return transactionTemplate.execute(status -> {
            DocumentRecord document = lockDocument(documentId);
            if (document.status() == DocumentStatus.DELETING) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "삭제 중인 문서는 수정할 수 없습니다."
                );
            }

            Integer nextVersion = jdbcTemplate.queryForObject("""
                    SELECT COALESCE(MAX(version), 0) + 1
                    FROM rag_document_version
                    WHERE document_id = ?
                    """, Integer.class, documentId);
            UUID versionId = UUID.randomUUID();
            jdbcTemplate.update("""
                    INSERT INTO rag_document_version(
                        id, document_id, version, content_sha256, content,
                        chunk_size, overlap_size, profile, embedding_model, status
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    versionId, documentId, nextVersion, contentHash, content,
                    chunkSize, overlap, profile, embeddingModel,
                    DocumentVersionStatus.INGESTING.name());
            jdbcTemplate.update("""
                    UPDATE rag_document
                    SET status = ?, updated_at = CURRENT_TIMESTAMP
                    WHERE id = ?
                    """, DocumentStatus.INGESTING.name(), documentId);
            return findVersion(versionId).orElseThrow();
        });
    }

    public void activateVersion(
            UUID documentId,
            UUID versionId,
            int chunkCount,
            String title,
            String category,
            String sourceName) {
        transactionTemplate.executeWithoutResult(status -> {
            VersionRecord version = findVersion(versionId)
                    .filter(candidate -> candidate.documentId().equals(documentId))
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.NOT_FOUND, "문서 버전을 찾을 수 없습니다."));

            int storedChunks = countChunks(versionId);
            if (storedChunks != chunkCount) {
                throw new IllegalStateException(
                        "저장한 chunk 수가 일치하지 않습니다. expected="
                                + chunkCount + ", actual=" + storedChunks
                );
            }

            jdbcTemplate.update("""
                    UPDATE rag_document_version
                    SET status = ?
                    WHERE document_id = ? AND status = ?
                    """,
                    DocumentVersionStatus.SUPERSEDED.name(), documentId,
                    DocumentVersionStatus.ACTIVE.name());
            jdbcTemplate.update("""
                    UPDATE vector_store
                    SET metadata = jsonb_set(metadata::jsonb, '{active}', 'false'::jsonb, true)
                    WHERE metadata->>'document_id' = ?
                    """, documentId.toString());
            jdbcTemplate.update("""
                    UPDATE vector_store
                    SET metadata = jsonb_set(metadata::jsonb, '{active}', 'true'::jsonb, true)
                    WHERE metadata->>'version_id' = ?
                    """, versionId.toString());
            jdbcTemplate.update("""
                    UPDATE rag_document_version
                    SET status = ?, chunk_count = ?, activated_at = CURRENT_TIMESTAMP,
                        error_message = NULL
                    WHERE id = ?
                    """, DocumentVersionStatus.ACTIVE.name(), chunkCount, versionId);
            jdbcTemplate.update("""
                    UPDATE rag_document
                    SET title = ?, category = ?, source_name = ?, current_version = ?,
                        status = ?, updated_at = CURRENT_TIMESTAMP
                    WHERE id = ?
                    """,
                    title, category, sourceName, version.version(),
                    DocumentStatus.ACTIVE.name(), documentId);
        });
    }

    public void markVersionFailed(UUID documentId, UUID versionId, Throwable error) {
        String message = Optional.ofNullable(error.getMessage())
                .orElse(error.getClass().getSimpleName());
        if (message.length() > 1000) {
            message = message.substring(0, 1000);
        }
        jdbcTemplate.update("""
                UPDATE rag_document_version
                SET status = ?, error_message = ?
                WHERE id = ?
                """, DocumentVersionStatus.FAILED.name(), message, versionId);
        jdbcTemplate.update("""
                UPDATE rag_document
                SET status = CASE WHEN current_version IS NULL THEN ? ELSE ? END,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, DocumentStatus.FAILED.name(), DocumentStatus.ACTIVE.name(), documentId);
    }

    public Optional<DuplicateDocument> findActiveByFingerprint(
            String contentHash,
            String profile,
            String embeddingModel) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject("""
                    SELECT d.id AS document_id, d.title, d.category, d.source_name,
                           v.id AS version_id, v.version, v.chunk_count
                    FROM rag_document d
                    JOIN rag_document_version v
                      ON v.document_id = d.id AND v.version = d.current_version
                    WHERE d.status = ? AND v.status = ?
                      AND v.content_sha256 = ? AND v.profile = ?
                      AND v.embedding_model = ?
                    ORDER BY d.created_at
                    LIMIT 1
                    """, (rs, rowNum) -> new DuplicateDocument(
                            rs.getObject("document_id", UUID.class),
                            rs.getObject("version_id", UUID.class),
                            rs.getInt("version"),
                            rs.getString("title"),
                            rs.getString("category"),
                            rs.getString("source_name"),
                            rs.getInt("chunk_count")
                    ), DocumentStatus.ACTIVE.name(), DocumentVersionStatus.ACTIVE.name(),
                    contentHash, profile, embeddingModel));
        }
        catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public List<DocumentRecord> findAll() {
        return jdbcTemplate.query("""
                SELECT id, title, category, source_name, current_version, status,
                       created_at, updated_at
                FROM rag_document
                ORDER BY updated_at DESC, id
                """, this::mapDocument);
    }

    public Optional<DocumentRecord> findDocument(UUID id) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject("""
                    SELECT id, title, category, source_name, current_version, status,
                           created_at, updated_at
                    FROM rag_document WHERE id = ?
                    """, this::mapDocument, id));
        }
        catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public List<VersionRecord> findVersions(UUID documentId) {
        return jdbcTemplate.query("""
                SELECT id, document_id, version, content_sha256, content,
                       chunk_size, overlap_size, profile, embedding_model, status,
                       chunk_count, error_message, created_at, activated_at
                FROM rag_document_version
                WHERE document_id = ?
                ORDER BY version DESC
                """, this::mapVersion, documentId);
    }

    public Optional<VersionRecord> findActiveVersion(UUID documentId) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject("""
                    SELECT v.id, v.document_id, v.version, v.content_sha256, v.content,
                           v.chunk_size, v.overlap_size, v.profile, v.embedding_model,
                           v.status, v.chunk_count, v.error_message,
                           v.created_at, v.activated_at
                    FROM rag_document_version v
                    JOIN rag_document d
                      ON d.id = v.document_id AND d.current_version = v.version
                    WHERE d.id = ? AND v.status = ?
                    """, this::mapVersion, documentId, DocumentVersionStatus.ACTIVE.name()));
        }
        catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public void markDeleting(UUID documentId) {
        int updated = jdbcTemplate.update("""
                UPDATE rag_document SET status = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status <> ?
                """, DocumentStatus.DELETING.name(), documentId, DocumentStatus.DELETING.name());
        if (updated == 0 && findDocument(documentId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "문서를 찾을 수 없습니다.");
        }
    }

    public void restoreActive(UUID documentId) {
        jdbcTemplate.update("""
                UPDATE rag_document
                SET status = CASE WHEN current_version IS NULL THEN ? ELSE ? END,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, DocumentStatus.FAILED.name(), DocumentStatus.ACTIVE.name(), documentId);
    }

    public void deleteDocument(UUID documentId) {
        jdbcTemplate.update("DELETE FROM rag_document WHERE id = ?", documentId);
    }

    public void deleteDocumentAndChunks(UUID documentId) {
        transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.update("""
                    DELETE FROM vector_store
                    WHERE metadata->>'document_id' = ?
                    """, documentId.toString());
            int deleted = jdbcTemplate.update(
                    "DELETE FROM rag_document WHERE id = ?",
                    documentId
            );
            if (deleted == 0) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "문서를 찾을 수 없습니다.");
            }
        });
    }

    public long countActiveDocuments() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rag_document WHERE status = ?",
                Long.class,
                DocumentStatus.ACTIVE.name()
        );
        return count == null ? 0 : count;
    }

    private DocumentRecord lockDocument(UUID documentId) {
        try {
            return jdbcTemplate.queryForObject("""
                    SELECT id, title, category, source_name, current_version, status,
                           created_at, updated_at
                    FROM rag_document WHERE id = ? FOR UPDATE
                    """, this::mapDocument, documentId);
        }
        catch (EmptyResultDataAccessException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "문서를 찾을 수 없습니다.");
        }
    }

    private Optional<VersionRecord> findVersion(UUID versionId) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject("""
                    SELECT id, document_id, version, content_sha256, content,
                           chunk_size, overlap_size, profile, embedding_model, status,
                           chunk_count, error_message, created_at, activated_at
                    FROM rag_document_version WHERE id = ?
                    """, this::mapVersion, versionId));
        }
        catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    private int countChunks(UUID versionId) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM vector_store
                WHERE metadata->>'version_id' = ?
                """, Long.class, versionId.toString());
        return count == null ? 0 : Math.toIntExact(count);
    }

    private DocumentRecord mapDocument(ResultSet rs, int rowNum) throws SQLException {
        Integer currentVersion = (Integer) rs.getObject("current_version");
        return new DocumentRecord(
                rs.getObject("id", UUID.class),
                rs.getString("title"),
                rs.getString("category"),
                rs.getString("source_name"),
                currentVersion,
                DocumentStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
        );
    }

    private VersionRecord mapVersion(ResultSet rs, int rowNum) throws SQLException {
        var activated = rs.getTimestamp("activated_at");
        return new VersionRecord(
                rs.getObject("id", UUID.class),
                rs.getObject("document_id", UUID.class),
                rs.getInt("version"),
                rs.getString("content_sha256"),
                rs.getBytes("content"),
                rs.getInt("chunk_size"),
                rs.getInt("overlap_size"),
                rs.getString("profile"),
                rs.getString("embedding_model"),
                DocumentVersionStatus.valueOf(rs.getString("status")),
                rs.getInt("chunk_count"),
                rs.getString("error_message"),
                rs.getTimestamp("created_at").toInstant(),
                activated == null ? null : activated.toInstant()
        );
    }

    public record DocumentRecord(
            UUID id,
            String title,
            String category,
            String sourceName,
            Integer currentVersion,
            DocumentStatus status,
            Instant createdAt,
            Instant updatedAt
    ) {}

    public record VersionRecord(
            UUID id,
            UUID documentId,
            int version,
            String contentSha256,
            byte[] content,
            int chunkSize,
            int overlap,
            String profile,
            String embeddingModel,
            DocumentVersionStatus status,
            int chunkCount,
            String errorMessage,
            Instant createdAt,
            Instant activatedAt
    ) {}

    public record DuplicateDocument(
            UUID documentId,
            UUID versionId,
            int version,
            String title,
            String category,
            String sourceName,
            int chunkCount
    ) {}
}
