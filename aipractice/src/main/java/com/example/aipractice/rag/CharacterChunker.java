package com.example.aipractice.rag;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

@Component
public class CharacterChunker {

    public List<Chunk> split(String text, int chunkSize, int overlap) {
        if (chunkSize < 100 || chunkSize > 4000) {
            throw new IllegalArgumentException("chunkSize는 100부터 4000 사이여야 합니다.");
        }
        if (overlap < 0 || overlap >= chunkSize) {
            throw new IllegalArgumentException("overlap은 0 이상 chunkSize 미만이어야 합니다.");
        }
        if (text == null || text.isBlank()) {
            return List.of();
        }

        List<Chunk> chunks = new ArrayList<>();
        int start = 0;
        int index = 0;

        while (start < text.length()) {
            int hardEnd = Math.min(start + chunkSize, text.length());
            int end = findNaturalBoundary(text, start, hardEnd, chunkSize);
            String content = text.substring(start, end).trim();

            if (!content.isEmpty()) {
                chunks.add(new Chunk(index++, content, start, end));
            }
            if (end >= text.length()) {
                break;
            }

            int nextStart = Math.max(0, end - overlap);
            start = nextStart > start ? nextStart : end;
        }

        return List.copyOf(chunks);
    }

    private int findNaturalBoundary(String text, int start, int hardEnd, int chunkSize) {
        if (hardEnd >= text.length()) {
            return text.length();
        }

        int minimumEnd = start + chunkSize / 2;
        int newline = text.lastIndexOf('\n', hardEnd);
        if (newline >= minimumEnd) {
            return newline + 1;
        }

        int whitespace = text.lastIndexOf(' ', hardEnd);
        return whitespace >= minimumEnd ? whitespace + 1 : hardEnd;
    }

    public record Chunk(int index, String content, int start, int end) {}
}
