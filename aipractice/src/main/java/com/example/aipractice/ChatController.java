package com.example.aipractice;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/chat")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }


    @PostMapping
    public ResponseEntity<ChatResponse> chat(
            @RequestBody ChatRequest request) {

        if (request.message() == null ||
            request.message().isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        return ResponseEntity.ok(
            chatService.chat(request.message())
        );
    }
    

    public record ChatRequest(String message) {}

    public enum Difficulty {
        BEGINNER,
        INTERMEDIATE,
        ADVANCED
    }

    public record ChatResponse(
            String topic,
            String explanation,
            Difficulty difficulty
    ) {}
}