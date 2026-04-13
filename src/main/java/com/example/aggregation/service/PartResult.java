package com.example.aggregation.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

public record PartResult(
    AggregationPart part,
    JsonNode response,
    Throwable error
) {

    public static PartResult success(AggregationPart part, JsonNode response) {
        return new PartResult(part, response, null);
    }

    public static PartResult failed(AggregationPart part, Throwable error) {
        return new PartResult(part, null, error);
    }

    public String name() {
        return part.name();
    }

    public boolean successful() {
        return error == null && response != null;
    }

    public void mergeInto(ObjectNode root) {
        if (successful()) {
            part.merge(root, response);
        }
    }
}
