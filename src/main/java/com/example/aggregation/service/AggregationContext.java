package com.example.aggregation.service;

import com.example.aggregation.web.DownstreamHeaders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

public record AggregationContext(
    ObjectNode inboundRequest,
    JsonNode mainResponse,
    DownstreamHeaders headers,
    ObjectMapper objectMapper,
    RequestedParts requestedParts
) {
}
