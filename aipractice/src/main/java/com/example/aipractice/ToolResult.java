package com.example.aipractice;

public record ToolResult<T>(
        boolean success,
        T data,
        String error
) {
    public static <T> ToolResult<T> ok(T data) {
        return new ToolResult<>(true, data, null);
    }

    public static <T> ToolResult<T> fail(String error) {
        return new ToolResult<>(false, null, error);
    }
}
