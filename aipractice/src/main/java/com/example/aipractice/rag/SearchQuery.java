package com.example.aipractice.rag;

import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

public record SearchQuery(
        String question,
        int topK,
        double similarityThreshold,
        SearchMode mode,
        String title,
        String category,
        String profile
) {
    public SearchQuery {
        if (!StringUtils.hasText(question)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "질문은 필수입니다.");
        }
        if (topK < 1 || topK > 50) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "topK는 1부터 50 사이여야 합니다.");
        }
        if (similarityThreshold < 0.0 || similarityThreshold > 1.0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "similarityThreshold는 0부터 1 사이여야 합니다.");
        }
        mode = mode == null ? SearchMode.HYBRID : mode;
        title = normalize(title);
        category = normalize(category);
        profile = normalize(profile);
    }

    public static SearchQuery of(
            String question,
            Integer topK,
            Double similarityThreshold,
            SearchMode mode,
            String title,
            String category,
            String profile) {

        return new SearchQuery(
                question,
                topK == null ? 5 : topK,
                similarityThreshold == null ? 0.5 : similarityThreshold,
                mode,
                title,
                category,
                profile
        );
    }

    public SearchQuery withQuestion(String newQuestion) {
        return new SearchQuery(
                newQuestion, topK, similarityThreshold, mode, title, category, profile
        );
    }

    private static String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
