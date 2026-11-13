package com.example.aipractice;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.example.aipractice.ChatController.ChatResponse;
import com.example.aipractice.ChatController.Difficulty;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.Mockito.*;
import org.springframework.http.MediaType;

@WebMvcTest(ChatController.class)
class ChatControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ChatService chatService;

    @Test
    void 빈문자열_요청시_400반환() throws Exception {

        mockMvc.perform(post("/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"message": ""}
                    """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(chatService);
    }

    @Test
    void 정상요청시_200반환() throws Exception {

        ChatResponse fakeResponse = new ChatResponse(
                "HashMap",
                "키와 값으로 데이터를 저장합니다.",
                Difficulty.BEGINNER
        );

        when(chatService.chat("HashMap이 뭐야?"))
                .thenReturn(fakeResponse);

        mockMvc.perform(post("/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"message": "HashMap이 뭐야?"}
                    """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.topic")
                        .value("HashMap"))
                .andExpect(jsonPath("$.explanation")
                        .value("키와 값으로 데이터를 저장합니다."))
                .andExpect(jsonPath("$.difficulty")
                        .value("BEGINNER"));
    }
}