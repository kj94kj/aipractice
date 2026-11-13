package com.example.aipractice.rag;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
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

    private final TokenTextSplitter textSplitter =
            TokenTextSplitter.builder()
                    .withChunkSize(500)
                    .withMinChunkSizeChars(100)
                    .withMinChunkLengthToEmbed(10)
                    .withMaxNumChunks(1000)
                    .withKeepSeparator(true)
                    .build();

    public DocumentIngestionService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    public IngestResult ingest(MultipartFile file) {
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
        Resource resource = namedResource(bytes, filename);
        List<Document> originalDocuments = readDocuments(resource, filename);

        originalDocuments.forEach(document -> {
            document.getMetadata().put("source", filename);
            document.getMetadata().put("document_type", extensionOf(filename));
        });

        List<Document> chunks = textSplitter
                .apply(originalDocuments)
                .stream()
                .filter(document -> StringUtils.hasText(document.getText()))
                .toList();

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
                originalDocuments.size(),
                chunks.size()
        );
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

    public record IngestResult(
            String filename,
            int originalDocumentCount,
            int chunkCount
    ) {}
}
