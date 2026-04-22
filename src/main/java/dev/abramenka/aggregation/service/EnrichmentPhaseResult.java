package dev.abramenka.aggregation.service;

import dev.abramenka.aggregation.enrichment.AggregationEnrichment;
import tools.jackson.databind.JsonNode;

sealed interface EnrichmentPhaseResult {

    AggregationEnrichment enrichment();

    default String name() {
        return enrichment().name();
    }

    static EnrichmentPhaseResult success(AggregationEnrichment enrichment, JsonNode response) {
        return new Success(enrichment, response);
    }

    static EnrichmentPhaseResult failed(AggregationEnrichment enrichment, Throwable error) {
        return new Failure(enrichment, error);
    }

    record Success(AggregationEnrichment enrichment, JsonNode response) implements EnrichmentPhaseResult {}

    record Failure(AggregationEnrichment enrichment, Throwable error) implements EnrichmentPhaseResult {}
}
