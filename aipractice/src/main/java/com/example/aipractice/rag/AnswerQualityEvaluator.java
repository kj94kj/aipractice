package com.example.aipractice.rag;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * A deterministic, inexpensive first-pass answer evaluator.
 *
 * <p>This is intentionally a proxy rather than a semantic LLM judge. Faithfulness checks
 * whether every factual sentence has a valid citation, while answer relevancy checks coverage
 * of human-authored key phrases. The report names both as proxies so they are not mistaken for
 * proof that a claim is semantically entailed by a chunk.</p>
 */
@Component
public class AnswerQualityEvaluator {

    private static final Pattern CITATION = Pattern.compile("\\[문서\\s+(\\d+)]");
    private static final Pattern CITATION_AFTER_PUNCTUATION = Pattern.compile(
            "([.!?])\\s*(\\[문서\\s+\\d+])");

    public AnswerScores evaluate(
            String answer,
            boolean expectedAnswerable,
            List<String> answerKeyPhrases,
            int retrievedDocumentCount) {

        boolean abstained = answer != null && answer.startsWith("근거 부족:");
        boolean abstentionCorrect = expectedAnswerable != abstained;

        if (answer == null || answer.isBlank()) {
            return new AnswerScores(false, abstentionCorrect, null, 0.0);
        }
        if (abstained) {
            return new AnswerScores(true, abstentionCorrect, null, expectedAnswerable ? 0.0 : 1.0);
        }

        Double faithfulness = citationCoverage(answer, retrievedDocumentCount);
        double relevancy = phraseCoverage(answer, answerKeyPhrases);
        return new AnswerScores(false, abstentionCorrect, faithfulness, relevancy);
    }

    private double citationCoverage(String answer, int retrievedDocumentCount) {
        // Models commonly write "claim. [문서 1]". Move that citation before the
        // punctuation so it is evaluated as evidence for the preceding claim.
        String citationAttached = CITATION_AFTER_PUNCTUATION.matcher(answer)
                .replaceAll(" $2$1");
        String[] sentences = citationAttached.split("(?<=[.!?])\\s+|\\R+");
        int factualSentences = 0;
        int validlyCitedSentences = 0;

        for (String sentence : sentences) {
            if (sentence.isBlank()) {
                continue;
            }
            factualSentences++;
            Matcher matcher = CITATION.matcher(sentence);
            boolean hasValidCitation = false;
            boolean hasInvalidCitation = false;
            while (matcher.find()) {
                int citation = Integer.parseInt(matcher.group(1));
                if (citation >= 1 && citation <= retrievedDocumentCount) {
                    hasValidCitation = true;
                }
                else {
                    hasInvalidCitation = true;
                }
            }
            if (hasValidCitation && !hasInvalidCitation) {
                validlyCitedSentences++;
            }
        }
        return factualSentences == 0 ? 0.0 : round((double) validlyCitedSentences / factualSentences);
    }

    private double phraseCoverage(String answer, List<String> answerKeyPhrases) {
        if (answerKeyPhrases == null || answerKeyPhrases.isEmpty()) {
            return 0.0;
        }
        String normalizedAnswer = normalize(answer);
        long matched = answerKeyPhrases.stream()
                .filter(phrase -> normalizedAnswer.contains(normalize(phrase)))
                .count();
        return round((double) matched / answerKeyPhrases.size());
    }

    private String normalize(String text) {
        return text.toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", "")
                .replaceAll("[.,!?]", "");
    }

    private double round(double value) {
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP).doubleValue();
    }

    public record AnswerScores(
            boolean abstained,
            boolean abstentionCorrect,
            Double faithfulnessProxy,
            double answerRelevancyProxy
    ) {}
}
