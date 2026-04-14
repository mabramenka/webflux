package com.example.aggregation.service;

import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

public interface AggregationPart {

    String name();

    boolean supports(AggregationContext context);

    Mono<JsonNode> fetch(AggregationContext context);

    void merge(ObjectNode root, JsonNode partResponse);
}
