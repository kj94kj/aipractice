package com.example.aipractice.rag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.example.aipractice.rag.RetrievalEvaluationService.EvaluationConfiguration;
import com.example.aipractice.rag.RetrievalEvaluationService.EvaluationQuestion;
import com.example.aipractice.rag.RetrievalEvaluationService.EvaluationRequest;

class RetrievalEvaluationServiceTest {

    @Test
    void Recall과_MRR을_계산한다() {
        RagSearchService searchService = mock(RagSearchService.class);
        when(searchService.search(any(SearchQuery.class))).thenReturn(List.of(
                hit("wrong.txt"),
                hit("right.txt")
        ));
        RetrievalEvaluationService service = new RetrievalEvaluationService(searchService);

        var report = service.evaluate(new EvaluationRequest(
                List.of(new EvaluationQuestion(
                        "q1", "질문", List.of("right.txt"), null, null
                )),
                List.of(new EvaluationConfiguration(
                        "baseline", SearchMode.VECTOR, 3, 0.0, null, null, null
                ))
        ));

        var result = report.comparison().get(0);
        assertEquals(1.0, result.recallAtK());
        assertEquals(1.0, result.hitRateAtK());
        assertEquals(0.5, result.mrr());
        assertEquals(2, result.questions().get(0).firstRelevantRank());
    }

    private SearchHit hit(String source) {
        return new SearchHit(
                source, "내용", source, source, "general", "1",
                0, 0, 2, "profile", 0.8, 0.8, null
        );
    }
}
