package com.example.aipractice.rag;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import com.example.aipractice.InvalidAiResponseException;

class GroundedAnswerGeneratorValidationTest {

    private final GroundedAnswerGenerator generator =
            new GroundedAnswerGenerator(
                    mock(ChatClient.Builder.class), mock(RagMetrics.class)
            );

    @Test
    void 인용이_있는_답변은_허용한다() {
        assertDoesNotThrow(() -> generator.validate("최대 5일입니다. [문서 1]"));
    }

    @Test
    void 인용도_보류표시도_없는_답변은_거부한다() {
        assertThrows(
                InvalidAiResponseException.class,
                () -> generator.validate("아마 최대 5일일 것입니다.")
        );
    }
}
