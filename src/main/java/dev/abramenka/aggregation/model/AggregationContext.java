package dev.abramenka.aggregation.model;

import tools.jackson.databind.JsonNode;

public record AggregationContext(
        JsonNode accountGroupResponse,
        ClientRequestContext clientRequestContext,
        EnrichmentSelection enrichmentSelection) {}
