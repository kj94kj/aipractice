package com.example.aipractice.rag;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.example.aipractice.rag.RagService.RagAnswer;
import com.example.aipractice.rag.RagService.SourceInfo;

@WebMvcTest(RagController.class)
class RagControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DocumentIngestionService ingestionService;

    @MockitoBean
    private RagService ragService;

    @Test
    void 질문이_비어있으면_400을_반환한다() throws Exception {
        mockMvc.perform(post("/api/rag/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"question":"", "topK":3}
                            """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(ragService);
    }

    @Test
    void 일반답변_RAG답변_출처를_반환한다() throws Exception {
        SourceInfo source = new SourceInfo(
                "doc-1",
                "company-policy.txt",
                "-",
                0.91,
                "남은 연차는 다음 해로 최대 5일까지 이월된다."
        );

        when(ragService.ask("연차는 몇 일까지 이월돼?", 3))
                .thenReturn(new RagAnswer(
                        "연차는 몇 일까지 이월돼?",
                        "회사 정책에 따라 다릅니다.",
                        "최대 5일까지 이월됩니다.",
                        List.of(source)
                ));

        mockMvc.perform(post("/api/rag/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {
                              "question":"연차는 몇 일까지 이월돼?",
                              "topK":3
                            }
                            """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.generalAnswer")
                        .value("회사 정책에 따라 다릅니다."))
                .andExpect(jsonPath("$.ragAnswer")
                        .value("최대 5일까지 이월됩니다."))
                .andExpect(jsonPath("$.sources[0].source")
                        .value("company-policy.txt"))
                .andExpect(jsonPath("$.sources[0].score")
                        .value(0.91));
    }
}
