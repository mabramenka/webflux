package com.example.aggregation.model;

import com.example.aggregation.enrichment.AggregationEnrichment;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

public record EnrichmentFetchResult(
    AggregationEnrichment enrichment,
    @Nullable JsonNode response,
    @Nullable Throwable error
) {

    public static EnrichmentFetchResult success(AggregationEnrichment enrichment, JsonNode response) {
        return new EnrichmentFetchResult(enrichment, response, null);
    }

    public static EnrichmentFetchResult failed(AggregationEnrichment enrichment, Throwable error) {
        return new EnrichmentFetchResult(enrichment, null, error);
    }

    public String name() {
        return enrichment.name();
    }

    public boolean successful() {
        return error == null && response != null;
    }

    public void mergeInto(ObjectNode root) {
        if (response != null && error == null) {
            enrichment.merge(root, response);
        }
    }
}
