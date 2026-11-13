package com.example.aipractice;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;

class ToolRegistrationTest {

    @Test
    void 세개의_Tool이_등록된다() {

        ProductTools productTools = new ProductTools();

        ToolCallback[] callbacks =
                ToolCallbacks.from(productTools);

        Set<String> names = Arrays.stream(callbacks)
                .map(callback ->
                        callback.getToolDefinition().name())
                .collect(Collectors.toSet());

        assertEquals(3, callbacks.length);

        assertEquals(
                Set.of(
                    "searchProducts",
                    "getProductDetail",
                    "checkStock"
                ),
                names
        );
    }
    
    @Test
    void ToolCallback으로_상품조회() {

        ToolCallback[] callbacks =
                ToolCallbacks.from(new ProductTools());

        ToolCallback searchTool = Arrays.stream(callbacks)
                .filter(c -> c.getToolDefinition()
                        .name().equals("searchProducts"))
                .findFirst()
                .orElseThrow();
        
        String result = searchTool.call("""
            {
              "category": "운동화",
              "maxPrice": 100000,
              "color": "검정"
            }
            """);

        assertTrue(result.contains("나이키 운동화"));
        assertTrue(result.contains("뉴발란스 운동화"));
        assertFalse(result.contains("아디다스 운동화"));
    }
    
    @Test
    void 두개의_Tool을_실행한다() {

        ToolCallback[] callbacks =
                ToolCallbacks.from(new ProductTools());

        ToolCallback detailTool = Arrays.stream(callbacks)
                .filter(c -> c.getToolDefinition()
                        .name().equals("getProductDetail"))
                .findFirst()
                .orElseThrow();

        ToolCallback stockTool = Arrays.stream(callbacks)
                .filter(c -> c.getToolDefinition()
                        .name().equals("checkStock"))
                .findFirst()
                .orElseThrow();

        String arguments = """
            {
              "name": "나이키 운동화"
            }
            """;

        String detail = detailTool.call(arguments);
        String stock = stockTool.call(arguments);

        assertTrue(detail.contains("나이키 운동화"));
        assertTrue(detail.contains("80000"));

        assertTrue(stock.contains("12"));
    }
}