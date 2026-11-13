package com.example.aipractice;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import com.example.aipractice.ChatController.ChatResponse;

@Service
public class ChatService {

    private final ChatClient chatClient;

    public ChatService(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    public ChatResponse chat(String message) {

        ChatResponse answer = chatClient.prompt()
                .system("""
                    당신은 한국어 프로그래밍 튜터입니다.
                    질문에 대해 topic, explanation,
                    difficulty를 제공하세요.
                    """)
                .user(message)
                .call()
                .entity(ChatResponse.class, spec -> spec
                        .useProviderStructuredOutput()
                        .validateSchema());

        if (answer == null ||
            answer.topic() == null ||
            answer.topic().isBlank() ||
            answer.explanation() == null ||
            answer.explanation().isBlank() ||
            answer.difficulty() == null) {

            throw new InvalidAiResponseException(
                "AI 응답값이 유효하지 않습니다."
            );
        }

        return answer;
    }
}
