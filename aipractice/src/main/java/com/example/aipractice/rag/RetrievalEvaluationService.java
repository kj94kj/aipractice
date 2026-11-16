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

import com.example.aipractice.rag.AnswerQualityEvaluator.AnswerScores;

@Service
public class RetrievalEvaluationService {

    private static final int RECALL_AT_3 = 3;
    private static final int RECALL_AT_5 = 5;

    private final RagSearchService searchService;
    private final GroundedAnswerGenerator answerGenerator;
    private final AnswerQualityEvaluator answerEvaluator;

    public RetrievalEvaluationService(
            RagSearchService searchService,
            GroundedAnswerGenerator answerGenerator,
            AnswerQualityEvaluator answerEvaluator) {
        this.searchService = searchService;
        this.answerGenerator = answerGenerator;
        this.answerEvaluator = answerEvaluator;
    }

    public EvaluationReport evaluate(EvaluationRequest request) {
        validate(request);
        boolean evaluateAnswers = !Boolean.FALSE.equals(request.evaluateAnswers());
        List<ConfigurationResult> results = request.configurations().stream()
                .map(configuration -> evaluateConfiguration(
                        configuration, request.questions(), evaluateAnswers))
                .toList();

        return new EvaluationReport(request.questions().size(), evaluateAnswers, results);
    }

    private ConfigurationResult evaluateConfiguration(
            EvaluationConfiguration configuration,
            List<EvaluationQuestion> questions,
            boolean evaluateAnswers) {

        List<QuestionResult> questionResults = new ArrayList<>();
        double recallAt3Sum = 0.0;
        double recallAt5Sum = 0.0;
        double reciprocalRankSum = 0.0;
        double retrievalMillisSum = 0.0;
        double generationMillisSum = 0.0;
        double faithfulnessSum = 0.0;
        double relevancySum = 0.0;
        int retrievalEvaluated = 0;
        int retrievalSucceeded = 0;
        int generationSucceeded = 0;
        int faithfulnessEvaluated = 0;
        int relevancyEvaluated = 0;
        int abstentionCorrect = 0;
        int errors = 0;

        for (EvaluationQuestion question : questions) {
            QuestionResult result = evaluateQuestion(configuration, question, evaluateAnswers);
            questionResults.add(result);

            if (result.error() != null) {
                errors++;
            }
            if (result.retrievalMillis() != null) {
                retrievalMillisSum += result.retrievalMillis();
                retrievalSucceeded++;
            }
            if (result.recallAt3() != null) {
                retrievalEvaluated++;
                recallAt3Sum += result.recallAt3();
                recallAt5Sum += result.recallAt5();
                reciprocalRankSum += result.firstRelevantRank() > 0
                        ? 1.0 / result.firstRelevantRank() : 0.0;
            }
            if (result.generationMillis() != null) {
                generationMillisSum += result.generationMillis();
                generationSucceeded++;
            }
            if (result.faithfulnessProxy() != null) {
                faithfulnessSum += result.faithfulnessProxy();
                faithfulnessEvaluated++;
            }
            if (result.answerRelevancyProxy() != null && question.isAnswerable()) {
                relevancySum += result.answerRelevancyProxy();
                relevancyEvaluated++;
            }
            if (result.abstentionCorrect() != null && result.abstentionCorrect()) {
                abstentionCorrect++;
            }
        }

        int count = questions.size();
        return new ConfigurationResult(
                configuration.name(),
                configuration.modeOrDefault(),
                configuration.topKOrDefault(),
                configuration.thresholdOrDefault(),
                configuration.profile(),
                configuration.promptProfileOrDefault(),
                average(recallAt3Sum, retrievalEvaluated),
                average(recallAt5Sum, retrievalEvaluated),
                average(reciprocalRankSum, retrievalEvaluated),
                average(retrievalMillisSum, retrievalSucceeded),
                evaluateAnswers ? average(generationMillisSum, generationSucceeded) : null,
                evaluateAnswers ? average(faithfulnessSum, faithfulnessEvaluated) : null,
                evaluateAnswers ? average(relevancySum, relevancyEvaluated) : null,
                evaluateAnswers ? average(abstentionCorrect, count) : null,
                round((double) errors / count),
                questionResults
        );
    }

    private QuestionResult evaluateQuestion(
            EvaluationConfiguration configuration,
            EvaluationQuestion question,
            boolean evaluateAnswers) {

        List<SearchHit> hits;
        double retrievalMillis;
        long retrievalStart = System.nanoTime();
        try {
            SearchQuery query = SearchQuery.of(
                    question.question(),
                    Math.max(RECALL_AT_5, configuration.topKOrDefault()),
                    configuration.thresholdOrDefault(),
                    configuration.modeOrDefault(),
                    firstNonBlank(question.title(), configuration.title()),
                    firstNonBlank(question.category(), configuration.category()),
                    configuration.profile()
            );
            hits = searchService.search(query);
            retrievalMillis = elapsedMillis(retrievalStart);
        }
        catch (RuntimeException error) {
            return new QuestionResult(
                    question.id(), question.question(), null, null, 0, List.of(),
                    null, null, null, null, null, null, null,
                    compactError(error)
            );
        }

        Set<String> expected = new HashSet<>(question.relevantSourcesOrEmpty());
        Double recallAt3 = expected.isEmpty() ? null : recallAt(hits, expected, RECALL_AT_3);
        Double recallAt5 = expected.isEmpty() ? null : recallAt(hits, expected, RECALL_AT_5);
        int firstRelevantRank = expected.isEmpty() ? 0 : firstRelevantRank(hits, expected);

        if (!evaluateAnswers) {
            return new QuestionResult(
                    question.id(), question.question(), recallAt3, recallAt5,
                    firstRelevantRank, sources(hits), roundMillis(retrievalMillis), null,
                    null, null, null, null, null, null
            );
        }

        List<SearchHit> answerHits = hits.stream()
                .limit(configuration.topKOrDefault())
                .toList();
        String answer;
        double generationMillis;
        try {
            long generationStart = System.nanoTime();
            answer = answerHits.isEmpty()
                    ? GroundedAnswerGenerator.ABSTENTION
                    : answerGenerator.generate(
                            List.of(), question.question(), answerHits,
                            configuration.promptProfileOrDefault());
            generationMillis = elapsedMillis(generationStart);
        }
        catch (RuntimeException error) {
            return new QuestionResult(
                    question.id(), question.question(), recallAt3, recallAt5,
                    firstRelevantRank, sources(hits), roundMillis(retrievalMillis),
                    null, null, null, null, null, null,
                    compactError(error)
            );
        }

        AnswerScores scores = answerEvaluator.evaluate(
                answer,
                question.isAnswerable(),
                question.answerKeyPhrasesOrEmpty(),
                answerHits.size()
        );
        return new QuestionResult(
                question.id(), question.question(), recallAt3, recallAt5,
                firstRelevantRank, sources(hits), roundMillis(retrievalMillis),
                roundMillis(generationMillis), answer, scores.abstained(),
                scores.abstentionCorrect(), scores.faithfulnessProxy(),
                scores.answerRelevancyProxy(), null
        );
    }

    private double recallAt(List<SearchHit> hits, Set<String> expected, int k) {
        Set<String> retrievedRelevant = new HashSet<>();
        hits.stream().limit(k)
                .map(SearchHit::source)
                .filter(expected::contains)
                .forEach(retrievedRelevant::add);
        return round((double) retrievedRelevant.size() / expected.size());
    }

    private int firstRelevantRank(List<SearchHit> hits, Set<String> expected) {
        for (int index = 0; index < hits.size(); index++) {
            if (expected.contains(hits.get(index).source())) {
                return index + 1;
            }
        }
        return 0;
    }

    private List<String> sources(List<SearchHit> hits) {
        return hits.stream().map(SearchHit::source).distinct().toList();
    }

    private void validate(EvaluationRequest request) {
        if (request == null || request.questions() == null || request.questions().isEmpty()) {
            throw badRequest("평가 질문이 한 개 이상 필요합니다.");
        }
        if (request.configurations() == null || request.configurations().isEmpty()) {
            throw badRequest("평가 설정이 한 개 이상 필요합니다.");
        }
        for (EvaluationQuestion question : request.questions()) {
            if (!StringUtils.hasText(question.question())) {
                throw badRequest("각 평가 질문에는 question이 필요합니다.");
            }
            if (question.isAnswerable() && question.relevantSourcesOrEmpty().isEmpty()) {
                throw badRequest("답변 가능한 질문에는 relevantSources가 필요합니다.");
            }
        }
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private String firstNonBlank(String first, String second) {
        return StringUtils.hasText(first) ? first : second;
    }

    private double elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000.0;
    }

    private double roundMillis(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private Double average(double total, int count) {
        return count == 0 ? null : round(total / count);
    }

    private double round(double value) {
        return BigDecimal.valueOf(value)
                .setScale(4, RoundingMode.HALF_UP)
                .doubleValue();
    }

    private String compactError(RuntimeException error) {
        String message = error.getMessage();
        return error.getClass().getSimpleName()
                + (StringUtils.hasText(message) ? ": " + message : "");
    }

    public record EvaluationRequest(
            List<EvaluationQuestion> questions,
            List<EvaluationConfiguration> configurations,
            Boolean evaluateAnswers
    ) {}

    public record EvaluationQuestion(
            String id,
            String question,
            List<String> relevantSources,
            String expectedAnswer,
            List<String> answerKeyPhrases,
            Boolean answerable,
            String title,
            String category
    ) {
        boolean isAnswerable() {
            return answerable == null ? relevantSources != null && !relevantSources.isEmpty() : answerable;
        }

        List<String> relevantSourcesOrEmpty() {
            return relevantSources == null ? List.of() : relevantSources;
        }

        List<String> answerKeyPhrasesOrEmpty() {
            return answerKeyPhrases == null ? List.of() : answerKeyPhrases;
        }
    }

    public record EvaluationConfiguration(
            String name,
            SearchMode mode,
            Integer topK,
            Double similarityThreshold,
            String title,
            String category,
            String profile,
            PromptProfile promptProfile
    ) {
        SearchMode modeOrDefault() {
            return mode == null ? SearchMode.HYBRID : mode;
        }

        int topKOrDefault() {
            return topK == null ? 5 : topK;
        }

        double thresholdOrDefault() {
            return similarityThreshold == null ? 0.5 : similarityThreshold;
        }

        PromptProfile promptProfileOrDefault() {
            return promptProfile == null ? PromptProfile.STRICT_CITATION : promptProfile;
        }
    }

    public record EvaluationReport(
            int questionCount,
            boolean answerEvaluationEnabled,
            List<ConfigurationResult> comparison
    ) {}

    public record ConfigurationResult(
            String name,
            SearchMode mode,
            int topK,
            double similarityThreshold,
            String profile,
            PromptProfile promptProfile,
            Double recallAt3,
            Double recallAt5,
            Double mrr,
            Double averageRetrievalMillis,
            Double averageGenerationMillis,
            Double faithfulnessProxy,
            Double answerRelevancyProxy,
            Double abstentionAccuracy,
            double errorRate,
            List<QuestionResult> questions
    ) {}

    public record QuestionResult(
            String id,
            String question,
            Double recallAt3,
            Double recallAt5,
            int firstRelevantRank,
            List<String> retrievedSources,
            Double retrievalMillis,
            Double generationMillis,
            String answer,
            Boolean abstained,
            Boolean abstentionCorrect,
            Double faithfulnessProxy,
            Double answerRelevancyProxy,
            String error
    ) {}
}
