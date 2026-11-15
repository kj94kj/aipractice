package com.example.aipractice.rag;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/rag/search")
public class RagSearchController {

    private final RagSearchService searchService;

    public RagSearchController(RagSearchService searchService) {
        this.searchService = searchService;
    }

    @PostMapping
    public ResponseEntity<SearchResponse> search(@RequestBody SearchRequest request) {
        SearchQuery query = request.toQuery();
        return ResponseEntity.ok(new SearchResponse(query, searchService.search(query)));
    }

    public record SearchRequest(
            String question,
            Integer topK,
            Double similarityThreshold,
            SearchMode mode,
            String title,
            String category,
            String profile
    ) {
        SearchQuery toQuery() {
            return SearchQuery.of(
                    question, topK, similarityThreshold, mode, title, category, profile
            );
        }
    }

    public record SearchResponse(SearchQuery settings, List<SearchHit> results) {}
}
