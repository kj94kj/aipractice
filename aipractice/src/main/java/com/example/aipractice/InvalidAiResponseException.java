package com.example.aipractice;

public class InvalidAiResponseException
        extends RuntimeException {

    public InvalidAiResponseException(String message) {
        super(message);
    }
}