package com.example.aipractice.rag;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.example.aipractice.rag.DocumentCatalogRepository.DocumentRecord;
import com.example.aipractice.rag.DocumentCatalogRepository.DuplicateDocument;
import com.example.aipractice.rag.DocumentCatalogRepository.VersionRecord;

@Service
public class DocumentIngestionService {

    private final VectorStore vectorStore;
    private final CharacterChunker chunker;
    private final DocumentCatalogRepository catalog;
    private final RagMetrics metrics;
    private final String embeddingModel;

    public DocumentIngestionService(
            VectorStore vectorStore,
            CharacterChunker chunker,
            DocumentCatalogRepository catalog,
            RagMetrics metrics,
            @Value("${rag.embedding-model:text-embedding-3-small}") String embeddingModel) {
        this.vectorStore = vectorStore;
        this.chunker = chunker;
        this.catalog = catalog;
        this.metrics = metrics;
        this.embeddingModel = embeddingModel;
    }

    public IngestResult ingest(MultipartFile file) {
        return ingest(file, null, null, 500, 50);
    }

    public IngestResult ingest(
            MultipartFile file,
            String requestedTitle,
            String requestedCategory,
            Integer requestedChunkSize,
            Integer requestedOverlap) {
        return metrics.ingestion("create", () -> {
            PreparedDocument prepared = prepare(
                    file, requestedTitle, requestedCategory,
                    requestedChunkSize, requestedOverlap, null
            );
            Optional<DuplicateDocument> duplicate = catalog.findActiveByFingerprint(
                    prepared.contentHash(), prepared.profile(), embeddingModel
            );
            if (duplicate.isPresent()) {
                return unchanged(duplicate.get(), prepared);
            }

            UUID documentId = UUID.randomUUID();
            catalog.createDocument(
                    documentId, prepared.title(), prepared.category(), prepared.filename()
            );
            return storeVersion(documentId, prepared, false, true);
        });
    }

    public IngestResult update(
            UUID documentId,
            MultipartFile file,
            String requestedTitle,
            String requestedCategory,
            Integer requestedChunkSize,
            Integer requestedOverlap) {
        return metrics.ingestion("update", () -> {
            DocumentRecord current = requireDocument(documentId);
            Optional<VersionRecord> active = catalog.findActiveVersion(documentId);
            PreparedDocument prepared = prepare(
                    file,
                    StringUtils.hasText(requestedTitle) ? requestedTitle : current.title(),
                    StringUtils.hasText(requestedCategory) ? requestedCategory : current.category(),
                    requestedChunkSize != null
                            ? requestedChunkSize
                            : active.map(VersionRecord::chunkSize).orElse(500),
                    requestedOverlap != null
                            ? requestedOverlap
                            : active.map(VersionRecord::overlap).orElse(50),
                    current.sourceName()
            );
            if (active.isPresent()
                    && active.get().contentSha256().equals(prepared.contentHash())
                    && active.get().profile().equals(prepared.profile())
                    && active.get().embeddingModel().equals(embeddingModel)
                    && current.title().equals(prepared.title())
                    && current.category().equals(prepared.category())
                    && current.sourceName().equals(prepared.filename())) {
                return unchanged(current, active.get());
            }
            return storeVersion(documentId, prepared, false, false);
        });
    }

    public IngestResult reindex(UUID documentId, Integer requestedChunkSize, Integer requestedOverlap) {
        return metrics.ingestion("reindex", () -> {
            DocumentRecord current = requireDocument(documentId);
            VersionRecord active = catalog.findActiveVersion(documentId)
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.CONFLICT, "재색인할 활성 버전이 없습니다."));
            int chunkSize = requestedChunkSize == null ? active.chunkSize() : requestedChunkSize;
            int overlap = requestedOverlap == null ? active.overlap() : requestedOverlap;
            PreparedDocument prepared = prepare(
                    active.content(), current.sourceName(), current.title(), current.category(),
                    chunkSize, overlap
            );
            if (active.profile().equals(prepared.profile())
                    && active.embeddingModel().equals(embeddingModel)) {
                return unchanged(current, active);
            }
            return storeVersion(documentId, prepared, true, false);
        });
    }

    public List<DocumentSummary> list() {
        return catalog.findAll().stream().map(this::toSummary).toList();
    }

    public DocumentDetails get(UUID documentId) {
        DocumentRecord document = requireDocument(documentId);
        List<VersionSummary> versions = catalog.findVersions(documentId).stream()
                .map(this::toVersionSummary)
                .toList();
        return new DocumentDetails(toSummary(document), versions);
    }

    public void delete(UUID documentId) {
        metrics.ingestion("delete", () -> {
            requireDocument(documentId);
            catalog.markDeleting(documentId);
            try {
                catalog.deleteDocumentAndChunks(documentId);
                return null;
            }
            catch (RuntimeException e) {
                catalog.restoreActive(documentId);
                throw e;
            }
        });
    }

    private IngestResult storeVersion(
            UUID documentId,
            PreparedDocument prepared,
            boolean reindexed,
            boolean newDocument) {
        VersionRecord version = catalog.createVersion(
                documentId,
                prepared.contentHash(),
                prepared.bytes(),
                prepared.chunkSize(),
                prepared.overlap(),
                prepared.profile(),
                embeddingModel
        );
        List<Document> chunks = createChunks(prepared, documentId, version);
        if (chunks.isEmpty()) {
            ResponseStatusException error = new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "문서에서 저장할 텍스트를 찾지 못했습니다."
            );
            catalog.markVersionFailed(documentId, version.id(), error);
            throw error;
        }

        try {
            vectorStore.add(chunks);
            catalog.activateVersion(
                    documentId, version.id(), chunks.size(),
                    prepared.title(), prepared.category(), prepared.filename()
            );
            return new IngestResult(
                    documentId, version.id(), version.version(), DocumentStatus.ACTIVE,
                    prepared.filename(), prepared.title(), prepared.category(), prepared.profile(),
                    prepared.contentHash(), embeddingModel, prepared.chunkSize(), prepared.overlap(),
                    prepared.originals().size(), chunks.size(), false, reindexed
            );
        }
        catch (DuplicateKeyException e) {
            cleanupFailedVersion(documentId, version.id(), chunks, e);
            IngestResult duplicate = catalog.findActiveByFingerprint(
                            prepared.contentHash(), prepared.profile(), embeddingModel)
                    .map(existing -> unchanged(existing, prepared))
                    .orElseThrow(() -> e);
            if (newDocument) {
                catalog.deleteDocument(documentId);
            }
            return duplicate;
        }
        catch (RuntimeException e) {
            cleanupFailedVersion(documentId, version.id(), chunks, e);
            throw e;
        }
    }

    private void cleanupFailedVersion(
            UUID documentId,
            UUID versionId,
            List<Document> chunks,
            RuntimeException originalError) {
        try {
            vectorStore.delete(chunks.stream().map(Document::getId).toList());
        }
        catch (RuntimeException cleanupError) {
            originalError.addSuppressed(cleanupError);
        }
        catalog.markVersionFailed(documentId, versionId, originalError);
    }

    private PreparedDocument prepare(
            MultipartFile file,
            String requestedTitle,
            String requestedCategory,
            Integer requestedChunkSize,
            Integer requestedOverlap,
            String defaultFilename) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "파일이 비어 있습니다.");
        }
        String filename = Optional.ofNullable(file.getOriginalFilename())
                .map(StringUtils::cleanPath)
                .filter(StringUtils::hasText)
                .orElseGet(() -> Objects.requireNonNullElse(defaultFilename, "unknown"));
        return prepare(
                readBytes(file), filename, requestedTitle, requestedCategory,
                requestedChunkSize == null ? 500 : requestedChunkSize,
                requestedOverlap == null ? 50 : requestedOverlap
        );
    }

    private PreparedDocument prepare(
            byte[] bytes,
            String filename,
            String requestedTitle,
            String requestedCategory,
            int chunkSize,
            int overlap) {
        if (filename.contains("..")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "올바르지 않은 파일명입니다.");
        }
        validateChunkOptions(chunkSize, overlap);
        String title = StringUtils.hasText(requestedTitle)
                ? requestedTitle.trim() : filenameWithoutExtension(filename);
        String category = StringUtils.hasText(requestedCategory)
                ? requestedCategory.trim() : "general";
        String profile = "c%d-o%d".formatted(chunkSize, overlap);
        Resource resource = namedResource(bytes, filename);
        return new PreparedDocument(
                bytes, filename, title, category, chunkSize, overlap, profile,
                sha256(bytes), readDocuments(resource, filename)
        );
    }

    private List<Document> createChunks(
            PreparedDocument prepared,
            UUID documentId,
            VersionRecord version) {
        List<Document> result = new ArrayList<>();
        for (int pageIndex = 0; pageIndex < prepared.originals().size(); pageIndex++) {
            Document original = prepared.originals().get(pageIndex);
            String page = metadata(original, "page_number", Integer.toString(pageIndex + 1));
            for (CharacterChunker.Chunk chunk : chunker.split(
                    original.getText(), prepared.chunkSize(), prepared.overlap())) {
                Map<String, Object> metadata = new HashMap<>(original.getMetadata());
                metadata.put("document_id", documentId.toString());
                metadata.put("version_id", version.id().toString());
                metadata.put("version", version.version());
                metadata.put("active", false);
                metadata.put("source", prepared.filename());
                metadata.put("title", prepared.title());
                metadata.put("category", prepared.category());
                metadata.put("document_type", extensionOf(prepared.filename()));
                metadata.put("page_number", page);
                metadata.put("chunk_index", chunk.index());
                metadata.put("chunk_start", chunk.start());
                metadata.put("chunk_end", chunk.end());
                metadata.put("chunk_size", prepared.chunkSize());
                metadata.put("overlap", prepared.overlap());
                metadata.put("profile", prepared.profile());
                metadata.put("content_sha256", prepared.contentHash());
                metadata.put("embedding_model", embeddingModel);

                String stableKey = String.join(
                        ":", version.id().toString(), page, Integer.toString(chunk.index())
                );
                String id = UUID.nameUUIDFromBytes(
                        stableKey.getBytes(StandardCharsets.UTF_8)
                ).toString();
                result.add(new Document(id, chunk.content(), metadata));
            }
        }
        return List.copyOf(result);
    }

    private IngestResult unchanged(DuplicateDocument duplicate, PreparedDocument prepared) {
        return new IngestResult(
                duplicate.documentId(), duplicate.versionId(), duplicate.version(),
                DocumentStatus.ACTIVE, duplicate.sourceName(), duplicate.title(),
                duplicate.category(), prepared.profile(), prepared.contentHash(), embeddingModel,
                prepared.chunkSize(), prepared.overlap(), prepared.originals().size(),
                duplicate.chunkCount(), true, false
        );
    }

    private IngestResult unchanged(DocumentRecord document, VersionRecord version) {
        return new IngestResult(
                document.id(), version.id(), version.version(), document.status(),
                document.sourceName(), document.title(), document.category(), version.profile(),
                version.contentSha256(), version.embeddingModel(), version.chunkSize(),
                version.overlap(), 0, version.chunkCount(), true, false
        );
    }

    private DocumentRecord requireDocument(UUID documentId) {
        return catalog.findDocument(documentId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "문서를 찾을 수 없습니다."));
    }

    private DocumentSummary toSummary(DocumentRecord document) {
        return new DocumentSummary(
                document.id(), document.title(), document.category(), document.sourceName(),
                document.currentVersion(), document.status(),
                document.createdAt(), document.updatedAt()
        );
    }

    private VersionSummary toVersionSummary(VersionRecord version) {
        return new VersionSummary(
                version.id(), version.version(), version.contentSha256(), version.chunkSize(),
                version.overlap(), version.profile(), version.embeddingModel(), version.status(),
                version.chunkCount(), version.errorMessage(), version.createdAt(), version.activatedAt()
        );
    }

    private void validateChunkOptions(int chunkSize, int overlap) {
        try {
            chunker.split("validation text", chunkSize, overlap);
        }
        catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
    }

    private List<Document> readDocuments(Resource resource, String filename) {
        return switch (extensionOf(filename)) {
            case "txt" -> {
                TextReader reader = new TextReader(resource);
                reader.getCustomMetadata().put("source", filename);
                yield reader.read();
            }
            case "pdf" -> new PagePdfDocumentReader(resource).read();
            default -> throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "txt 또는 pdf 파일만 지원합니다.");
        };
    }

    private Resource namedResource(byte[] bytes, String filename) {
        return new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
    }

    private byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        }
        catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "파일을 읽을 수 없습니다.", e);
        }
    }

    private String extensionOf(String filename) {
        int index = filename.lastIndexOf('.');
        return index < 0 || index == filename.length() - 1
                ? "" : filename.substring(index + 1).toLowerCase(Locale.ROOT);
    }

    private String filenameWithoutExtension(String filename) {
        int index = filename.lastIndexOf('.');
        return index > 0 ? filename.substring(0, index) : filename;
    }

    private String metadata(Document document, String key, String defaultValue) {
        return Optional.ofNullable(document.getMetadata().get(key))
                .map(Object::toString)
                .orElse(defaultValue);
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", e);
        }
    }

    private record PreparedDocument(
            byte[] bytes,
            String filename,
            String title,
            String category,
            int chunkSize,
            int overlap,
            String profile,
            String contentHash,
            List<Document> originals
    ) {}

    public record IngestResult(
            UUID documentId,
            UUID versionId,
            int version,
            DocumentStatus status,
            String filename,
            String title,
            String category,
            String profile,
            String contentSha256,
            String embeddingModel,
            int chunkSize,
            int overlap,
            int originalDocumentCount,
            int chunkCount,
            boolean unchanged,
            boolean reindexed
    ) {}

    public record DocumentSummary(
            UUID documentId,
            String title,
            String category,
            String filename,
            Integer currentVersion,
            DocumentStatus status,
            Instant createdAt,
            Instant updatedAt
    ) {}

    public record VersionSummary(
            UUID versionId,
            int version,
            String contentSha256,
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

    public record DocumentDetails(DocumentSummary document, List<VersionSummary> versions) {}
}
