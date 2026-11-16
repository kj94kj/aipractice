package com.example.aipractice;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.SQLException;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.jdbc.CannotGetJdbcConnectionException;

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

    @Test
    void LLM_timeout을_504로_변환한다() {
        var response = handler.handleTimeout(new TimeoutException("slow llm"));

        assertEquals(HttpStatus.GATEWAY_TIMEOUT, response.getStatusCode());
        assertEquals("AI 서비스 응답 시간이 초과되었습니다.", response.getBody().get("error"));
    }

    @Test
    void DB_연결실패를_503으로_변환하고_내부정보는_숨긴다() {
        var exception = new CannotGetJdbcConnectionException(
                "jdbc:postgresql://secret-host:5432/ragdb",
                new SQLException("connection refused")
        );

        var response = handler.handleDatabaseUnavailable(exception);

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals(
                "문서 저장소를 일시적으로 이용할 수 없습니다.",
                response.getBody().get("error")
        );
    }
}
