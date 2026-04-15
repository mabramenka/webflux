package com.example.aggregation.application;

import com.example.aggregation.application.enrichment.AggregationEnrichment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

public record EnrichmentResult(
    AggregationEnrichment enrichment,
    JsonNode response,
    Throwable error
) {

    public static EnrichmentResult success(AggregationEnrichment enrichment, JsonNode response) {
        return new EnrichmentResult(enrichment, response, null);
    }

    public static EnrichmentResult failed(AggregationEnrichment enrichment, Throwable error) {
        return new EnrichmentResult(enrichment, null, error);
    }

    public String name() {
        return enrichment.name();
    }

    public boolean successful() {
        return error == null && response != null;
    }

    public void mergeInto(ObjectNode root) {
        if (successful()) {
            enrichment.merge(root, response);
        }
    }
}
