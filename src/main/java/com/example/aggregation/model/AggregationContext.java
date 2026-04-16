package com.example.aggregation.model;

import com.example.aggregation.client.ClientRequestContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

public record AggregationContext(
        ObjectNode inboundRequest,
        JsonNode accountGroupResponse,
        ClientRequestContext clientRequestContext,
        EnrichmentSelection enrichmentSelection) {}
