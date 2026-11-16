package com.example.aipractice.rag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class AnswerQualityEvaluatorTest {

    private final AnswerQualityEvaluator evaluator = new AnswerQualityEvaluator();

    @Test
    void 유효한_문장별_인용과_핵심문구_포함률을_계산한다() {
        var result = evaluator.evaluate(
                "연차는 최대 5일까지 이월됩니다. [문서 1] 기한은 12월 31일입니다. [문서 2]",
                true,
                List.of("최대 5일", "12월 31일"),
                2
        );

        assertFalse(result.abstained());
        assertTrue(result.abstentionCorrect());
        assertEquals(1.0, result.faithfulnessProxy());
        assertEquals(1.0, result.answerRelevancyProxy());
    }

    @Test
    void 답변불가_질문을_보류하면_정답으로_처리한다() {
        var result = evaluator.evaluate(
                GroundedAnswerGenerator.ABSTENTION,
                false,
                List.of(),
                3
        );

        assertTrue(result.abstained());
        assertTrue(result.abstentionCorrect());
        assertEquals(1.0, result.answerRelevancyProxy());
    }

    @Test
    void 인용이_없는_문장이_있으면_faithfulness가_낮아진다() {
        var result = evaluator.evaluate(
                "연차는 5일까지 이월됩니다. 기한은 12월 31일입니다. [문서 1]",
                true,
                List.of("5일", "12월 31일"),
                1
        );

        assertEquals(0.5, result.faithfulnessProxy());
    }
}
