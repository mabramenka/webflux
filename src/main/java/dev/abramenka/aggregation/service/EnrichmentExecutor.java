package dev.abramenka.aggregation.service;

import dev.abramenka.aggregation.enrichment.AggregationEnrichment;
import dev.abramenka.aggregation.model.AggregationContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
@RequiredArgsConstructor
@Slf4j
class EnrichmentExecutor {

    private final AggregationPartMetrics metrics;

    Mono<EnrichmentPhaseResult> fetch(AggregationEnrichment enrichment, AggregationContext context) {
        return enrichment
                .fetch(context)
                .doOnSuccess(response -> recordFetch(enrichment.name(), "success"))
                .map(response -> EnrichmentPhaseResult.success(enrichment, response))
                .switchIfEmpty(Mono.fromSupplier(() -> {
                    recordFetch(enrichment.name(), "empty");
                    log.warn("Optional aggregation enrichment '{}' returned an empty response", enrichment.name());
                    return EnrichmentPhaseResult.failed(
                            enrichment, new IllegalStateException("empty response from enrichment"));
                }))
                .onErrorResume(Exception.class, ex -> {
                    recordFetch(enrichment.name(), "failure");
                    log.warn("Optional aggregation enrichment '{}' failed and will be skipped", enrichment.name(), ex);
                    return Mono.just(EnrichmentPhaseResult.failed(enrichment, ex));
                });
    }

    private void recordFetch(String partName, String outcome) {
        metrics.record(partName, outcome);
    }
}
