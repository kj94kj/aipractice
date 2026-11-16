package com.example.aipractice.rag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

class RagSearchServiceTest {

    private final VectorStore vectorStore = mock(VectorStore.class);
    private final KeywordSearchRepository keywordRepository = mock(KeywordSearchRepository.class);
    private final RagMetrics metrics = mock(RagMetrics.class);
    private final RagSearchService service = new RagSearchService(
            vectorStore, keywordRepository, metrics
    );

    RagSearchServiceTest() {
        when(metrics.search(any(SearchMode.class), any()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(1)).get());
    }

    @Test
    void hybrid는_양쪽_검색에_등장한_문서를_먼저_반환한다() {
        Document vectorOnly = document("00000000-0000-0000-0000-000000000001", "벡터", 0.9);
        Document both = document("00000000-0000-0000-0000-000000000002", "공통", 0.8);
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(vectorOnly, both));

        SearchHit keywordBoth = hit(both.getId(), "공통", 0.7);
        SearchHit keywordOnly = hit("00000000-0000-0000-0000-000000000003", "키워드", 0.6);
        when(keywordRepository.search(any(SearchQuery.class), eq(20)))
                .thenReturn(List.of(keywordBoth, keywordOnly));

        var results = service.search(SearchQuery.of(
                "질문", 3, 0.5, SearchMode.HYBRID, null, null, null
        ));

        assertEquals(both.getId(), results.get(0).documentId());
        assertNotNull(results.get(0).vectorScore());
        assertNotNull(results.get(0).keywordScore());
    }

    private Document document(String id, String text, double score) {
        return Document.builder()
                .id(id)
                .text(text)
                .metadata(Map.of("source", text + ".txt"))
                .score(score)
                .build();
    }

    private SearchHit hit(String id, String content, double score) {
        return new SearchHit(
                id, content, content + ".txt", content, "general", "1",
                0, 0, content.length(), "c300-o30", score, null, score
        );
    }
}
