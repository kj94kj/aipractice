package com.example.aipractice.rag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

class DocumentIngestionServiceTest {

    private final VectorStore vectorStore = mock(VectorStore.class);
    private final DocumentIngestionService service =
            new DocumentIngestionService(vectorStore, new CharacterChunker());

    @Test
    void 텍스트파일을_Chunk로_나누어_저장한다() {
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
}
