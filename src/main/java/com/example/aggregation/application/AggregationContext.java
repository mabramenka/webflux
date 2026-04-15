package com.example.aggregation.application;

import com.example.aggregation.downstream.DownstreamRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

public record AggregationContext(
    ObjectNode inboundRequest,
    JsonNode mainResponse,
    DownstreamRequest downstreamRequest,
    RequestedEnrichments requestedEnrichments
) {
}
