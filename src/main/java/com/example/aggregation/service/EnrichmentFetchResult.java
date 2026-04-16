package com.example.aggregation.service;

import com.example.aggregation.enrichment.AggregationEnrichment;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

record EnrichmentFetchResult(
        AggregationEnrichment enrichment,
        @Nullable JsonNode response,
        @Nullable Throwable error) {

    static EnrichmentFetchResult success(AggregationEnrichment enrichment, JsonNode response) {
        return new EnrichmentFetchResult(enrichment, response, null);
    }

    static EnrichmentFetchResult failed(AggregationEnrichment enrichment, Throwable error) {
        return new EnrichmentFetchResult(enrichment, null, error);
    }

    String name() {
        return enrichment.name();
    }

    boolean successful() {
        return error == null && response != null;
    }

    void mergeInto(ObjectNode root) {
        if (response != null && error == null) {
            enrichment.merge(root, response);
        }
    }
}
