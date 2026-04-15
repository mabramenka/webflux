package com.example.aggregation.application;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import tools.jackson.databind.JsonNode;

public record RequestedEnrichments(boolean all, Set<String> names) {

    private static final String INCLUDE_FIELD = "include";

    public static RequestedEnrichments from(JsonNode inboundRequest) {
        JsonNode includeNode = inboundRequest.path(INCLUDE_FIELD);
        if (includeNode.isMissingNode() || includeNode.isNull()) {
            return new RequestedEnrichments(true, Set.of());
        }

        if (!includeNode.isArray()) {
            throw new IllegalArgumentException("'include' must be an array of aggregation enrichment names");
        }

        Set<String> requested = new LinkedHashSet<>();
        includeNode.forEach(item -> {
            String name = item.stringValueOpt()
                .map(String::trim)
                .orElseThrow(() -> new IllegalArgumentException("'include' values must be non-blank strings"));
            if (name.isBlank()) {
                throw new IllegalArgumentException("'include' values must be non-blank strings");
            }
            requested.add(name);
        });

        return new RequestedEnrichments(false, Collections.unmodifiableSet(requested));
    }

    public boolean includes(String name) {
        return all || names.contains(name);
    }
}
