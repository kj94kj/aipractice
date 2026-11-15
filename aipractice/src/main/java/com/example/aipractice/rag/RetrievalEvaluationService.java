package com.example.aipractice.rag;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RetrievalEvaluationService {

    private final RagSearchService searchService;

    public RetrievalEvaluationService(RagSearchService searchService) {
        this.searchService = searchService;
    }

    public EvaluationReport evaluate(EvaluationRequest request) {
        validate(request);
        List<ConfigurationResult> results = request.configurations().stream()
                .map(configuration -> evaluateConfiguration(configuration, request.questions()))
                .toList();

        return new EvaluationReport(request.questions().size(), results);
    }

    private ConfigurationResult evaluateConfiguration(
            EvaluationConfiguration configuration,
            List<EvaluationQuestion> questions) {

        List<QuestionResult> questionResults = new ArrayList<>();
        double recallSum = 0.0;
        double reciprocalRankSum = 0.0;
        int hits = 0;

        for (EvaluationQuestion question : questions) {
            SearchQuery query = SearchQuery.of(
                    question.question(),
                    configuration.topK(),
                    configuration.similarityThreshold(),
                    configuration.mode(),
                    firstNonBlank(question.title(), configuration.title()),
                    firstNonBlank(question.category(), configuration.category()),
                    configuration.profile()
            );
            List<SearchHit> searchHits = searchService.search(query);
            Set<String> expected = new HashSet<>(question.relevantSources());
            Set<String> retrievedRelevant = new HashSet<>();
            int firstRelevantRank = 0;

            for (int index = 0; index < searchHits.size(); index++) {
                SearchHit hit = searchHits.get(index);
                if (expected.contains(hit.source())) {
                    retrievedRelevant.add(hit.source());
                    if (firstRelevantRank == 0) {
                        firstRelevantRank = index + 1;
                    }
                }
            }

            double recall = (double) retrievedRelevant.size() / expected.size();
            boolean hit = firstRelevantRank > 0;
            recallSum += recall;
            hits += hit ? 1 : 0;
            reciprocalRankSum += hit ? 1.0 / firstRelevantRank : 0.0;

            questionResults.add(new QuestionResult(
                    question.id(),
                    question.question(),
                    round(recall),
                    firstRelevantRank,
                    searchHits.stream().map(SearchHit::source).distinct().toList()
            ));
        }

        int count = questions.size();
        return new ConfigurationResult(
                configuration.name(),
                configuration.mode() == null ? SearchMode.HYBRID : configuration.mode(),
                configuration.topK() == null ? 5 : configuration.topK(),
                configuration.similarityThreshold() == null ? 0.5 : configuration.similarityThreshold(),
                configuration.profile(),
                round(recallSum / count),
                round((double) hits / count),
                round(reciprocalRankSum / count),
                questionResults
        );
    }

    private void validate(EvaluationRequest request) {
        if (request == null || request.questions() == null || request.questions().isEmpty()) {
            throw badRequest("평가 질문이 한 개 이상 필요합니다.");
        }
        if (request.configurations() == null || request.configurations().isEmpty()) {
            throw badRequest("평가 설정이 한 개 이상 필요합니다.");
        }
        for (EvaluationQuestion question : request.questions()) {
            if (!StringUtils.hasText(question.question())
                    || question.relevantSources() == null
                    || question.relevantSources().isEmpty()) {
                throw badRequest("각 평가 질문에는 question과 relevantSources가 필요합니다.");
            }
        }
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private String firstNonBlank(String first, String second) {
        return StringUtils.hasText(first) ? first : second;
    }

    private double round(double value) {
        return BigDecimal.valueOf(value)
                .setScale(4, RoundingMode.HALF_UP)
                .doubleValue();
    }

    public record EvaluationRequest(
            List<EvaluationQuestion> questions,
            List<EvaluationConfiguration> configurations
    ) {}

    public record EvaluationQuestion(
            String id,
            String question,
            List<String> relevantSources,
            String title,
            String category
    ) {}

    public record EvaluationConfiguration(
            String name,
            SearchMode mode,
            Integer topK,
            Double similarityThreshold,
            String title,
            String category,
            String profile
    ) {}

    public record EvaluationReport(
            int questionCount,
            List<ConfigurationResult> comparison
    ) {}

    public record ConfigurationResult(
            String name,
            SearchMode mode,
            int topK,
            double similarityThreshold,
            String profile,
            double recallAtK,
            double hitRateAtK,
            double mrr,
            List<QuestionResult> questions
    ) {}

    public record QuestionResult(
            String id,
            String question,
            double recall,
            int firstRelevantRank,
            List<String> retrievedSources
    ) {}
}
