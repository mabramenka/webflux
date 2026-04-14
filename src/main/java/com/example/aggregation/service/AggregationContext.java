package com.example.aggregation.service;

import com.example.aggregation.web.DownstreamRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

public record AggregationContext(
    ObjectNode inboundRequest,
    JsonNode mainResponse,
    DownstreamRequest downstreamRequest,
    RequestedParts requestedParts
) {
}
