package com.example.aipractice.rag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CharacterChunkerTest {

    private final CharacterChunker chunker = new CharacterChunker();

    @Test
    void overlap만큼_앞_chunk의_내용을_다음_chunk가_포함한다() {
        String text = "가".repeat(250);

        var chunks = chunker.split(text, 100, 20);

        assertEquals(3, chunks.size());
        assertEquals(0, chunks.get(0).start());
        assertEquals(80, chunks.get(1).start());
        assertEquals(160, chunks.get(2).start());
        assertEquals(100, chunks.get(0).content().length());
    }

    @Test
    void overlap이_chunkSize_이상이면_거부한다() {
        var exception = assertThrows(
                IllegalArgumentException.class,
                () -> chunker.split("가".repeat(200), 100, 100)
        );

        assertTrue(exception.getMessage().contains("overlap"));
    }
}
