package com.example.aipractice.rag;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import reactor.core.publisher.Flux;

@Service
public class RagChatService {

    private static final Duration STREAM_TIMEOUT = Duration.ofSeconds(45);

    private final RagSearchService searchService;
    private final GroundedAnswerGenerator answerGenerator;
    private final ChatMemory chatMemory;

    public RagChatService(
            RagSearchService searchService,
            GroundedAnswerGenerator answerGenerator,
            ChatMemory chatMemory) {

        this.searchService = searchService;
        this.answerGenerator = answerGenerator;
        this.chatMemory = chatMemory;
    }

    public ChatAnswer chat(String requestedConversationId, SearchQuery query) {
        PreparedChat prepared = prepare(requestedConversationId, query);
        if (prepared.hits().isEmpty()) {
            remember(prepared.conversationId(), query.question(), GroundedAnswerGenerator.ABSTENTION);
            return new ChatAnswer(
                    prepared.conversationId(),
                    query.question(),
                    GroundedAnswerGenerator.ABSTENTION,
                    true,
                    query,
                    List.of()
            );
        }

        String answer = answerGenerator.generate(
                prepared.history(), query.question(), prepared.hits()
        );
        remember(prepared.conversationId(), query.question(), answer);

        return new ChatAnswer(
                prepared.conversationId(),
                query.question(),
                answer,
                answer.startsWith("근거 부족:"),
                query,
                toSources(prepared.hits())
        );
    }

    public Flux<StreamEvent> stream(String requestedConversationId, SearchQuery query) {
        PreparedChat prepared = prepare(requestedConversationId, query);
        List<SourceInfo> sources = toSources(prepared.hits());

        if (prepared.hits().isEmpty()) {
            remember(prepared.conversationId(), query.question(), GroundedAnswerGenerator.ABSTENTION);
            return Flux.just(
                    StreamEvent.sources(prepared.conversationId(), sources),
                    StreamEvent.token(GroundedAnswerGenerator.ABSTENTION),
                    StreamEvent.done()
            );
        }

        StringBuilder completeAnswer = new StringBuilder();
        Flux<StreamEvent> tokens = answerGenerator
                .stream(prepared.history(), query.question(), prepared.hits())
                .timeout(STREAM_TIMEOUT)
                .doOnNext(completeAnswer::append)
                .map(StreamEvent::token);

        Flux<StreamEvent> completion = Flux.defer(() -> {
            String answer = completeAnswer.toString();
            answerGenerator.validate(answer);
            remember(prepared.conversationId(), query.question(), answer);
            return Flux.just(StreamEvent.done());
        });

        return Flux.concat(
                        Flux.just(StreamEvent.sources(prepared.conversationId(), sources)),
                        tokens,
                        completion
                )
                .onErrorResume(error -> Flux.just(StreamEvent.error(
                        error instanceof java.util.concurrent.TimeoutException
                                ? "AI 응답 시간이 초과되었습니다."
                                : "AI 응답을 생성하지 못했습니다."
                )));
    }

    public void clear(String conversationId) {
        if (StringUtils.hasText(conversationId)) {
            chatMemory.clear(conversationId);
        }
    }

    private PreparedChat prepare(String requestedConversationId, SearchQuery query) {
        String conversationId = StringUtils.hasText(requestedConversationId)
                ? requestedConversationId.trim()
                : UUID.randomUUID().toString();
        List<Message> history = List.copyOf(chatMemory.get(conversationId));
        String retrievalQuestion = contextualSearchQuestion(history, query.question());
        List<SearchHit> hits = searchService.search(query.withQuestion(retrievalQuestion));
        return new PreparedChat(conversationId, history, hits);
    }

    private String contextualSearchQuestion(List<Message> history, String currentQuestion) {
        List<Message> reversed = new ArrayList<>(history);
        Collections.reverse(reversed);
        return reversed.stream()
                .filter(message -> message.getMessageType() == MessageType.USER)
                .map(Message::getText)
                .filter(StringUtils::hasText)
                .findFirst()
                .map(previous -> previous + "\n후속 질문: " + currentQuestion)
                .orElse(currentQuestion);
    }

    private void remember(String conversationId, String question, String answer) {
        chatMemory.add(conversationId, List.of(
                new UserMessage(question),
                new AssistantMessage(answer)
        ));
    }

    private List<SourceInfo> toSources(List<SearchHit> hits) {
        return java.util.stream.IntStream.range(0, hits.size())
                .mapToObj(index -> {
                    SearchHit hit = hits.get(index);
                    return new SourceInfo(
                            index + 1,
                            hit.documentId(),
                            hit.source(),
                            hit.title(),
                            hit.category(),
                            hit.page(),
                            hit.chunkIndex(),
                            hit.chunkStart(),
                            hit.chunkEnd(),
                            hit.score(),
                            hit.excerpt()
                    );
                })
                .toList();
    }

    private record PreparedChat(
            String conversationId,
            List<Message> history,
            List<SearchHit> hits
    ) {}

    public record ChatAnswer(
            String conversationId,
            String question,
            String answer,
            boolean abstained,
            SearchQuery searchSettings,
            List<SourceInfo> sources
    ) {}

    public record SourceInfo(
            int citationNumber,
            String documentId,
            String source,
            String title,
            String category,
            String page,
            Integer chunkIndex,
            Integer chunkStart,
            Integer chunkEnd,
            double score,
            String excerpt
    ) {}

    public record StreamEvent(
            String type,
            String conversationId,
            String content,
            List<SourceInfo> sources
    ) {
        static StreamEvent sources(String conversationId, List<SourceInfo> sources) {
            return new StreamEvent("SOURCES", conversationId, null, sources);
        }

        static StreamEvent token(String content) {
            return new StreamEvent("TOKEN", null, content, null);
        }

        static StreamEvent done() {
            return new StreamEvent("DONE", null, null, null);
        }

        static StreamEvent error(String message) {
            return new StreamEvent("ERROR", null, message, null);
        }
    }
}
