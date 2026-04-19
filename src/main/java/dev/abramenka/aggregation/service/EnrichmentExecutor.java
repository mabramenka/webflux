package dev.abramenka.aggregation.service;

import dev.abramenka.aggregation.enrichment.AggregationEnrichment;
import dev.abramenka.aggregation.model.AggregationContext;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
@RequiredArgsConstructor
@Slf4j
public class EnrichmentExecutor {

    private final MeterRegistry meterRegistry;

    Mono<EnrichmentFetchResult> fetch(AggregationEnrichment enrichment, AggregationContext context) {
        return enrichment
                .fetch(context)
                .switchIfEmpty(Mono.error(() -> new IllegalStateException(
                        "Optional aggregation enrichment '" + enrichment.name() + "' returned an empty response")))
                .doOnSuccess(response -> recordFetch(enrichment.name(), "SUCCESS"))
                .map(response -> EnrichmentFetchResult.success(enrichment, response))
                .onErrorResume(Exception.class, ex -> {
                    recordFetch(enrichment.name(), "ERROR");
                    log.warn("Optional aggregation enrichment '{}' failed and will be skipped", enrichment.name(), ex);
                    return Mono.just(EnrichmentFetchResult.failed(enrichment, ex));
                });
    }

    private void recordFetch(String enrichmentName, String outcome) {
        meterRegistry
                .counter("aggregation.enrichment.requests", "enrichment", enrichmentName, "outcome", outcome)
                .increment();
    }
}
