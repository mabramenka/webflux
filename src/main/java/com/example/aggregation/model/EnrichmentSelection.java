package com.example.aggregation.model;

import com.example.aggregation.error.InvalidAggregationRequestException;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import tools.jackson.databind.JsonNode;

public record EnrichmentSelection(boolean all, Set<String> names) {

    private static final String INCLUDE_FIELD = "include";

    public static EnrichmentSelection from(JsonNode inboundRequest) {
        JsonNode includeNode = inboundRequest.path(INCLUDE_FIELD);
        if (includeNode.isMissingNode() || includeNode.isNull()) {
            return new EnrichmentSelection(true, Set.of());
        }

        if (!includeNode.isArray()) {
            throw new InvalidAggregationRequestException("'include' must be an array of aggregation enrichment names");
        }

        Set<String> requested = new LinkedHashSet<>();
        includeNode.forEach(item -> {
            String name = item.stringValueOpt()
                    .map(String::trim)
                    .orElseThrow(
                            () -> new InvalidAggregationRequestException("'include' values must be non-blank strings"));
            if (name.isBlank()) {
                throw new InvalidAggregationRequestException("'include' values must be non-blank strings");
            }
            requested.add(name);
        });

        return new EnrichmentSelection(false, Collections.unmodifiableSet(requested));
    }

    public boolean includes(String name) {
        return all || names.contains(name);
    }
}
