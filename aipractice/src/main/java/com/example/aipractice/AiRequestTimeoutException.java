package com.example.aipractice;

public class AiRequestTimeoutException extends RuntimeException {

    public AiRequestTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
