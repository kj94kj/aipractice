package com.example.aipractice.rag;

import java.util.function.Supplier;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

@Component
public class RagMetrics {

    private final MeterRegistry registry;

    public RagMetrics(MeterRegistry registry, DocumentCatalogRepository repository) {
        this.registry = registry;
        Gauge.builder("rag.documents.active", repository,
                        DocumentCatalogRepository::countActiveDocuments)
                .description("현재 ACTIVE 상태인 논리 문서 수")
                .register(registry);
    }

    public <T> T ingestion(String operation, Supplier<T> action) {
        Timer.Sample sample = Timer.start(registry);
        try {
            T result = action.get();
            registry.counter("rag.ingestion.operations",
                    "operation", operation, "outcome", "success").increment();
            sample.stop(registry.timer("rag.ingestion.duration",
                    "operation", operation, "outcome", "success"));
            return result;
        }
        catch (RuntimeException e) {
            registry.counter("rag.ingestion.operations",
                    "operation", operation, "outcome", "failure",
                    "error.type", errorType(e)).increment();
            sample.stop(registry.timer("rag.ingestion.duration",
                    "operation", operation, "outcome", "failure"));
            throw e;
        }
    }

    public <T> T search(SearchMode mode, Supplier<T> action) {
        Timer.Sample sample = Timer.start(registry);
        try {
            T result = action.get();
            sample.stop(registry.timer("rag.search.duration",
                    "mode", mode.name(), "outcome", "success"));
            return result;
        }
        catch (RuntimeException e) {
            sample.stop(registry.timer("rag.search.duration",
                    "mode", mode.name(), "outcome", "failure"));
            throw e;
        }
    }

    public void searchResults(SearchMode mode, int count) {
        registry.summary("rag.search.results", "mode", mode.name()).record(count);
    }

    public Timer.Sample startLlm() {
        return Timer.start(registry);
    }

    public void stopLlm(Timer.Sample sample, String outcome, Throwable error) {
        sample.stop(registry.timer("rag.llm.duration", "outcome", outcome));
        if (error != null) {
            registry.counter("rag.llm.errors", "error.type", errorType(error)).increment();
        }
    }

    private String errorType(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof java.util.concurrent.TimeoutException
                    || current instanceof com.example.aipractice.AiRequestTimeoutException) {
                return "timeout";
            }
            current = current.getCause();
        }
        return error.getClass().getSimpleName();
    }
}
