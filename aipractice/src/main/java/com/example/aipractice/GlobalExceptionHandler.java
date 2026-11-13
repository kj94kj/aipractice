package com.example.aipractice;

import java.util.Map;

import org.springframework.ai.retry.TransientAiException;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(TransientAiException.class)
    public ResponseEntity<Map<String, String>> handleTransient(
            TransientAiException e) {

        return ResponseEntity
                .status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", "AI 서비스 일시적 오류"));
    }

    @ExceptionHandler(NonTransientAiException.class)
    public ResponseEntity<Map<String, String>> handleNonTransient(
            NonTransientAiException e) {

        return ResponseEntity
                .status(HttpStatus.BAD_GATEWAY)
                .body(Map.of("error", "AI 서비스 호출 실패"));
    }
    
    @ExceptionHandler(InvalidAiResponseException.class)
    public ResponseEntity<Map<String, String>> handleInvalidResponse(
            InvalidAiResponseException e) {

        return ResponseEntity
                .status(HttpStatus.BAD_GATEWAY)
                .body(Map.of(
                    "error", "AI 응답 형식이 올바르지 않습니다."
                ));
    }
    
    @ExceptionHandler(ToolExecutionException.class)
    public ResponseEntity<Map<String, String>> handleToolError(
            ToolExecutionException e) {

        // 실제 운영에서는 로거를 사용해 상세 원인을 기록
        // 클라이언트에는 내부 오류 정보를 노출하지 않음

        return ResponseEntity
                .status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of(
                    "error", "상품 조회 서비스를 이용할 수 없습니다."
                ));
    }
}