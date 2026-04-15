package com.example.aggregation.enrichment;

import com.example.aggregation.model.AggregationContext;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

public interface AggregationEnrichment {

    String name();

    boolean supports(AggregationContext context);

    Mono<JsonNode> fetch(AggregationContext context);

    void merge(ObjectNode root, JsonNode enrichmentResponse);
}
