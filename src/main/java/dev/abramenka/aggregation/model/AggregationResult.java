package dev.abramenka.aggregation.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import tools.jackson.databind.node.ObjectNode;

public record AggregationResult(ObjectNode data, Map<String, PartOutcome> partOutcomes) {

    public AggregationResult {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(partOutcomes, "partOutcomes");
        partOutcomes = Collections.unmodifiableMap(new LinkedHashMap<>(partOutcomes));
    }
}
