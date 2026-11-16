package com.example.aipractice.rag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.example.aipractice.rag.DocumentCatalogRepository.VersionRecord;

class DocumentIngestionServiceTest {

    private final VectorStore vectorStore = mock(VectorStore.class);
    private final DocumentCatalogRepository catalog = mock(DocumentCatalogRepository.class);
    private final RagMetrics metrics = mock(RagMetrics.class);
    private final DocumentIngestionService service =
            new DocumentIngestionService(
                    vectorStore, new CharacterChunker(), catalog, metrics,
                    "text-embedding-3-small"
            );

    DocumentIngestionServiceTest() {
        when(metrics.ingestion(anyString(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(1)).get());
    }

    @Test
    void 텍스트파일을_Chunk로_나누어_저장한다() {
        UUID versionId = UUID.randomUUID();
        when(catalog.findActiveByFingerprint(anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(catalog.createVersion(
                org.mockito.ArgumentMatchers.any(), anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(), anyString(), anyString()
        )).thenReturn(new VersionRecord(
                versionId, UUID.randomUUID(), 1, "hash", new byte[0],
                500, 50, "c500-o50", "text-embedding-3-small",
                DocumentVersionStatus.INGESTING, 0, null, Instant.now(), null
        ));
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "policy.txt",
                "text/plain",
                "남은 연차는 다음 해로 최대 5일까지 이월됩니다."
                        .getBytes()
        );

        var result = service.ingest(file);

        assertEquals("policy.txt", result.filename());
        assertEquals(1, result.originalDocumentCount());
        assertEquals(1, result.chunkCount());
        verify(vectorStore).add(anyList());
    }

    @Test
    void 지원하지_않는_파일은_400예외가_발생한다() {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "policy.csv",
                "text/csv",
                "name,value".getBytes()
        );

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.ingest(file)
        );

        assertEquals(400, exception.getStatusCode().value());
    }

    @Test
    void 같은_내용과_Profile은_기존_문서를_반환한다() {
        UUID documentId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        when(catalog.findActiveByFingerprint(anyString(), anyString(), anyString()))
                .thenReturn(Optional.of(new DocumentCatalogRepository.DuplicateDocument(
                        documentId, versionId, 2, "연차 규정", "hr", "policy.txt", 3
                )));
        MockMultipartFile file = new MockMultipartFile(
                "file", "policy.txt", "text/plain", "같은 문서".getBytes()
        );

        var result = service.ingest(file, "연차 규정", "hr", 500, 50);

        assertTrue(result.unchanged());
        assertEquals(documentId, result.documentId());
        assertEquals(2, result.version());
        verify(vectorStore, never()).add(anyList());
    }

    @Test
    void Embedding_저장에_실패하면_새_Version을_FAILED로_표시한다() {
        UUID documentId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        when(catalog.findActiveByFingerprint(anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(catalog.createVersion(
                org.mockito.ArgumentMatchers.any(), anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(), anyString(), anyString()
        )).thenReturn(new VersionRecord(
                versionId, documentId, 1, "hash", new byte[0],
                500, 50, "c500-o50", "text-embedding-3-small",
                DocumentVersionStatus.INGESTING, 0, null, Instant.now(), null
        ));
        org.mockito.Mockito.doThrow(new IllegalStateException("embedding down"))
                .when(vectorStore).add(anyList());
        MockMultipartFile file = new MockMultipartFile(
                "file", "policy.txt", "text/plain", "장애 테스트 문서".getBytes()
        );

        assertThrows(IllegalStateException.class, () -> service.ingest(file));

        verify(vectorStore).delete(anyList());
        verify(catalog).markVersionFailed(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(versionId),
                org.mockito.ArgumentMatchers.any(IllegalStateException.class)
        );
    }
}
