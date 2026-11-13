package com.example.aipractice;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.tool.definition.ToolDefinition;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler =
            new GlobalExceptionHandler();

    @Test
    void Tool_예외를_503으로_변환한다() {

        ToolDefinition definition = ToolDefinition.builder()
                .name("checkProductSystem")
                .description("상품 시스템 상태 확인")
                .inputSchema("{}")
                .build();

        ToolExecutionException exception =
                new ToolExecutionException(
                    definition,
                    new ProductServiceException("상품 DB 연결 실패")
                );

        var response = handler.handleToolError(exception);

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,
                response.getStatusCode());

        assertEquals(
                "상품 조회 서비스를 이용할 수 없습니다.",
                response.getBody().get("error")
        );
    }
}
