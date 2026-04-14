package com.example.aggregation.service.part;

import com.example.aggregation.service.AggregationPart;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

public abstract class EmbeddedJsonPart implements AggregationPart {

    protected abstract String targetField();

    @Override
    public void merge(ObjectNode root, JsonNode partResponse) {
        root.set(targetField(), partResponse);
    }
}
