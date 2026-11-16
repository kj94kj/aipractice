package com.example.aipractice.rag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter.Expression;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder.Op;
import org.springframework.stereotype.Service;

@Service
public class RagSearchService {

    private static final int RRF_K = 60;

    private final VectorStore vectorStore;
    private final KeywordSearchRepository keywordRepository;
    private final RagMetrics metrics;

    public RagSearchService(
            VectorStore vectorStore,
            KeywordSearchRepository keywordRepository,
            RagMetrics metrics) {
        this.vectorStore = vectorStore;
        this.keywordRepository = keywordRepository;
        this.metrics = metrics;
    }

    public List<SearchHit> search(SearchQuery query) {
        List<SearchHit> hits = metrics.search(query.mode(), () -> doSearch(query));
        metrics.searchResults(query.mode(), hits.size());
        return hits;
    }

    private List<SearchHit> doSearch(SearchQuery query) {
        return switch (query.mode()) {
            case VECTOR -> vectorSearch(query, query.topK());
            case KEYWORD -> keywordRepository.search(query, query.topK());
            case HYBRID -> hybridSearch(query);
        };
    }

    private List<SearchHit> hybridSearch(SearchQuery query) {
        int candidateLimit = Math.max(20, query.topK() * 3);
        List<SearchHit> vectors = vectorSearch(query, candidateLimit);
        List<SearchHit> keywords = keywordRepository.search(query, candidateLimit);

        Map<String, MutableFusionHit> fused = new LinkedHashMap<>();
        addRanked(fused, vectors, true);
        addRanked(fused, keywords, false);

        return fused.values().stream()
                .map(MutableFusionHit::toSearchHit)
                .sorted(Comparator.comparingDouble(SearchHit::score).reversed())
                .limit(query.topK())
                .toList();
    }

    private void addRanked(
            Map<String, MutableFusionHit> fused,
            List<SearchHit> hits,
            boolean vector) {

        for (int index = 0; index < hits.size(); index++) {
            SearchHit hit = hits.get(index);
            MutableFusionHit fusion = fused.computeIfAbsent(
                    hit.documentId(), ignored -> new MutableFusionHit(hit)
            );
            fusion.fusionScore += 1.0 / (RRF_K + index + 1);
            if (vector) {
                fusion.vectorScore = hit.vectorScore();
            }
            else {
                fusion.keywordScore = hit.keywordScore();
            }
        }
    }

    private List<SearchHit> vectorSearch(SearchQuery query, int limit) {
        SearchRequest.Builder request = SearchRequest.builder()
                .query(query.question())
                .topK(limit)
                .similarityThreshold(query.similarityThreshold());

        Expression filter = metadataFilter(query);
        if (filter != null) {
            request.filterExpression(filter);
        }

        return vectorStore.similaritySearch(request.build()).stream()
                .map(this::toVectorHit)
                .toList();
    }

    private Expression metadataFilter(SearchQuery query) {
        FilterExpressionBuilder builder = new FilterExpressionBuilder();
        List<Op> filters = new ArrayList<>();

        filters.add(builder.eq("active", true));
        addFilter(filters, builder, "title", query.title());
        addFilter(filters, builder, "category", query.category());
        addFilter(filters, builder, "profile", query.profile());

        if (filters.isEmpty()) {
            return null;
        }

        Op combined = filters.get(0);
        for (int index = 1; index < filters.size(); index++) {
            combined = builder.and(combined, filters.get(index));
        }
        return combined.build();
    }

    private void addFilter(
            List<Op> filters,
            FilterExpressionBuilder builder,
            String key,
            String value) {

        if (value != null) {
            filters.add(builder.eq(key, value));
        }
    }

    private SearchHit toVectorHit(Document document) {
        double score = Objects.requireNonNullElse(document.getScore(), 0.0);
        return new SearchHit(
                document.getId(),
                Objects.requireNonNullElse(document.getText(), ""),
                metadata(document, "source", "unknown"),
                metadata(document, "title", "unknown"),
                metadata(document, "category", "general"),
                metadata(document, "page_number", "-"),
                integerMetadata(document, "chunk_index"),
                integerMetadata(document, "chunk_start"),
                integerMetadata(document, "chunk_end"),
                metadata(document, "profile", "unknown"),
                score,
                score,
                null
        );
    }

    private String metadata(Document document, String key, String defaultValue) {
        return Objects.toString(document.getMetadata().get(key), defaultValue);
    }

    private Integer integerMetadata(Document document, String key) {
        Object value = document.getMetadata().get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        return value == null ? null : Integer.valueOf(value.toString());
    }

    private static final class MutableFusionHit {
        private final SearchHit hit;
        private double fusionScore;
        private Double vectorScore;
        private Double keywordScore;

        private MutableFusionHit(SearchHit hit) {
            this.hit = hit;
        }

        private SearchHit toSearchHit() {
            return hit.withFusionScore(fusionScore, vectorScore, keywordScore);
        }
    }
}
