package dev.abramenka.aggregation.part.execution;

import dev.abramenka.aggregation.model.AggregationPart;
import java.util.List;
import java.util.Objects;

record AggregationPartExecutionPlan(List<List<AggregationPart>> levels) {

    AggregationPartExecutionPlan {
        levels = Objects.requireNonNull(levels, "levels").stream()
                .map(List::copyOf)
                .toList();
    }
}
