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
                hit("wrong-2.txt"),
                hit("wrong-3.txt"),
                hit("right.txt"),
                hit("wrong-4.txt")
        ));
        RetrievalEvaluationService service = new RetrievalEvaluationService(
                searchService,
                mock(GroundedAnswerGenerator.class),
                new AnswerQualityEvaluator()
        );

        var report = service.evaluate(new EvaluationRequest(
                List.of(new EvaluationQuestion(
                        "q1", "질문", List.of("right.txt"), "정답",
                        List.of("정답"), true, null, null
                )),
                List.of(new EvaluationConfiguration(
                        "baseline", SearchMode.VECTOR, 3, 0.0,
                        null, null, null, PromptProfile.BASELINE
                )),
                false
        ));

        var result = report.comparison().get(0);
        assertEquals(0.0, result.recallAt3());
        assertEquals(1.0, result.recallAt5());
        assertEquals(0.25, result.mrr());
        assertEquals(4, result.questions().get(0).firstRelevantRank());
    }

    private SearchHit hit(String source) {
        return new SearchHit(
                source, "내용", source, source, "general", "1",
                0, 0, 2, "profile", 0.8, 0.8, null
        );
    }
}
