package com.example.aipractice;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/tool")
public class ToolController {

    private final ChatClient chatClient;
    private final ProductTools productTools;

    public ToolController(
            ChatClient.Builder builder,
            ProductTools productTools) {

        this.chatClient = builder.build();
        this.productTools = productTools;
    }

    @PostMapping
    public String chat(@RequestBody String message) {

        return chatClient.prompt()
        		.system("""
        			    당신은 상품 검색 도우미입니다.
        			    상품 정보는 제공된 Tool을 통해 조회하세요.
        			    Tool 결과의 success가 false이면
        			    해당 오류 내용을 사용자에게 설명하세요.
        			    조회되지 않은 상품 정보나 재고를 지어내지 마세요.
        			    """)
                .user(message)
                .tools(productTools)
                .call()
                .content();
    }
}