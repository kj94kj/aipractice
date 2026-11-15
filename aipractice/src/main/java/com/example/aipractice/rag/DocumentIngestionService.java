package com.example.aipractice.rag;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class DocumentIngestionService {

    private final VectorStore vectorStore;
    private final CharacterChunker chunker;

    public DocumentIngestionService(VectorStore vectorStore, CharacterChunker chunker) {
        this.vectorStore = vectorStore;
        this.chunker = chunker;
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

        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "파일이 비어 있습니다."
            );
        }

        String filename = Optional
                .ofNullable(file.getOriginalFilename())
                .map(StringUtils::cleanPath)
                .filter(StringUtils::hasText)
                .orElse("unknown");

        if (filename.contains("..")) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "올바르지 않은 파일명입니다."
            );
        }

        byte[] bytes = readBytes(file);
        int chunkSize = requestedChunkSize == null ? 500 : requestedChunkSize;
        int overlap = requestedOverlap == null ? 50 : requestedOverlap;
        validateChunkOptions(chunkSize, overlap);

        String title = StringUtils.hasText(requestedTitle)
                ? requestedTitle.trim()
                : filenameWithoutExtension(filename);
        String category = StringUtils.hasText(requestedCategory)
                ? requestedCategory.trim()
                : "general";
        String profile = "c%d-o%d".formatted(chunkSize, overlap);
        String contentHash = sha256(bytes);

        Resource resource = namedResource(bytes, filename);
        List<Document> originalDocuments = readDocuments(resource, filename);
        List<Document> chunks = createChunks(
                originalDocuments, filename, title, category,
                profile, contentHash, chunkSize, overlap
        );

        if (chunks.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "문서에서 저장할 텍스트를 찾지 못했습니다."
            );
        }

        // 각 Chunk를 임베딩한 뒤 pgvector에 저장한다.
        vectorStore.add(chunks);

        return new IngestResult(
                filename,
                title,
                category,
                profile,
                chunkSize,
                overlap,
                originalDocuments.size(),
                chunks.size()
        );
    }

    private List<Document> createChunks(
            List<Document> originals,
            String filename,
            String title,
            String category,
            String profile,
            String contentHash,
            int chunkSize,
            int overlap) {

        List<Document> result = new ArrayList<>();

        for (int pageIndex = 0; pageIndex < originals.size(); pageIndex++) {
            Document original = originals.get(pageIndex);
            String text = original.getText();
            String page = metadata(original, "page_number", Integer.toString(pageIndex + 1));

            for (CharacterChunker.Chunk chunk : chunker.split(text, chunkSize, overlap)) {
                Map<String, Object> metadata = new HashMap<>(original.getMetadata());
                metadata.put("source", filename);
                metadata.put("title", title);
                metadata.put("category", category);
                metadata.put("document_type", extensionOf(filename));
                metadata.put("page_number", page);
                metadata.put("chunk_index", chunk.index());
                metadata.put("chunk_start", chunk.start());
                metadata.put("chunk_end", chunk.end());
                metadata.put("chunk_size", chunkSize);
                metadata.put("overlap", overlap);
                metadata.put("profile", profile);
                metadata.put("content_sha256", contentHash);

                String stableKey = String.join(
                        ":", contentHash, filename, title, category,
                        profile, page, Integer.toString(chunk.index())
                );
                String id = UUID.nameUUIDFromBytes(stableKey.getBytes(StandardCharsets.UTF_8)).toString();
                result.add(new Document(id, chunk.content(), metadata));
            }
        }

        return List.copyOf(result);
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
                    HttpStatus.BAD_REQUEST,
                    "txt 또는 pdf 파일만 지원합니다."
            );
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
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "파일을 읽을 수 없습니다.",
                    e
            );
        }
    }

    private String extensionOf(String filename) {
        int index = filename.lastIndexOf('.');

        if (index < 0 || index == filename.length() - 1) {
            return "";
        }

        return filename.substring(index + 1).toLowerCase(Locale.ROOT);
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
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            return java.util.HexFormat.of().formatHex(digest);
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", e);
        }
    }

    public record IngestResult(
            String filename,
            String title,
            String category,
            String profile,
            int chunkSize,
            int overlap,
            int originalDocumentCount,
            int chunkCount
    ) {}
}
