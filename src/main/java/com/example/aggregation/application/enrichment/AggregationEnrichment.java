package com.example.aggregation.application.enrichment;

import com.example.aggregation.application.AggregationContext;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

public interface AggregationEnrichment {

    String name();

    boolean supports(AggregationContext context);

    Mono<JsonNode> fetch(AggregationContext context);

    void merge(ObjectNode root, JsonNode enrichmentResponse);
}
