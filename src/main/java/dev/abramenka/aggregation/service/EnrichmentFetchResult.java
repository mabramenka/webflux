package dev.abramenka.aggregation.service;

import dev.abramenka.aggregation.enrichment.AggregationEnrichment;
import tools.jackson.databind.JsonNode;

sealed interface EnrichmentFetchResult {

    AggregationEnrichment enrichment();

    default String name() {
        return enrichment().name();
    }

    static EnrichmentFetchResult success(AggregationEnrichment enrichment, JsonNode response) {
        return new Success(enrichment, response);
    }

    static EnrichmentFetchResult failed(AggregationEnrichment enrichment, Throwable error) {
        return new Failure(enrichment, error);
    }

    record Success(AggregationEnrichment enrichment, JsonNode response) implements EnrichmentFetchResult {}

    record Failure(AggregationEnrichment enrichment, Throwable error) implements EnrichmentFetchResult {}
}
