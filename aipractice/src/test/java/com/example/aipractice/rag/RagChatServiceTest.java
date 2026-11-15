package com.example.aipractice.rag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;

class RagChatServiceTest {

    private final RagSearchService searchService = mock(RagSearchService.class);
    private final GroundedAnswerGenerator answerGenerator = mock(GroundedAnswerGenerator.class);
    private final org.springframework.ai.chat.memory.ChatMemory memory =
            MessageWindowChatMemory.builder()
                    .chatMemoryRepository(new InMemoryChatMemoryRepository())
                    .maxMessages(12)
                    .build();
    private final RagChatService service = new RagChatService(
            searchService, answerGenerator, memory
    );

    @Test
    void 검색결과가_없으면_LLM을_호출하지_않고_답변을_보류한다() {
        when(searchService.search(any(SearchQuery.class))).thenReturn(List.of());

        var answer = service.chat("conversation-1", query("창립자는 누구야?"));

        assertTrue(answer.abstained());
        assertEquals(GroundedAnswerGenerator.ABSTENTION, answer.answer());
        verify(answerGenerator, never()).generate(anyList(), any(), anyList());
    }

    @Test
    void 같은_대화의_후속질문은_직전질문과_함께_검색한다() {
        SearchHit hit = new SearchHit(
                "id", "최대 5일", "company.txt", "연차", "hr", "1",
                0, 0, 5, "c300-o30", 0.9, 0.9, null
        );
        when(searchService.search(any(SearchQuery.class))).thenReturn(List.of(hit));
        when(answerGenerator.generate(anyList(), any(), anyList()))
                .thenReturn("최대 5일입니다. [문서 1]");

        service.chat("conversation-2", query("연차를 얼마나 이월해?"));
        service.chat("conversation-2", query("언제까지 써야 해?"));

        verify(searchService).search(eq(query(
                "연차를 얼마나 이월해?\n후속 질문: 언제까지 써야 해?"
        )));
    }

    private SearchQuery query(String question) {
        return SearchQuery.of(
                question, 5, 0.5, SearchMode.HYBRID, null, null, null
        );
    }
}
