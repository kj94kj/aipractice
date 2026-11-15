package com.example.aipractice.rag;

public record SearchHit(
        String documentId,
        String content,
        String source,
        String title,
        String category,
        String page,
        Integer chunkIndex,
        Integer chunkStart,
        Integer chunkEnd,
        String profile,
        double score,
        Double vectorScore,
        Double keywordScore
) {
    public SearchHit withFusionScore(double fusionScore, Double newVectorScore, Double newKeywordScore) {
        return new SearchHit(
                documentId, content, source, title, category, page,
                chunkIndex, chunkStart, chunkEnd, profile,
                fusionScore, newVectorScore, newKeywordScore
        );
    }

    public String excerpt() {
        if (content == null || content.length() <= 300) {
            return content == null ? "" : content;
        }
        return content.substring(0, 300) + "...";
    }
}
