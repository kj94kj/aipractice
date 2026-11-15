package com.example.aipractice.rag;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.aipractice.rag.RagChatService.ChatAnswer;
import com.example.aipractice.rag.RagChatService.StreamEvent;

import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/rag/chat")
public class RagChatController {

    private final RagChatService chatService;

    public RagChatController(RagChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping
    public ResponseEntity<ChatAnswer> chat(@RequestBody ChatRequest request) {
        return ResponseEntity.ok(chatService.chat(request.conversationId(), request.toQuery()));
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<StreamEvent> stream(@RequestBody ChatRequest request) {
        return chatService.stream(request.conversationId(), request.toQuery());
    }

    @DeleteMapping("/conversations/{conversationId}")
    public ResponseEntity<Void> clear(@PathVariable String conversationId) {
        chatService.clear(conversationId);
        return ResponseEntity.noContent().build();
    }

    public record ChatRequest(
            String conversationId,
            String question,
            Integer topK,
            Double similarityThreshold,
            SearchMode mode,
            String title,
            String category,
            String profile
    ) {
        SearchQuery toQuery() {
            return SearchQuery.of(
                    question, topK, similarityThreshold, mode, title, category, profile
            );
        }
    }
}
