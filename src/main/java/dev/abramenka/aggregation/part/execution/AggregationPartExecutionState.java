package dev.abramenka.aggregation.part.execution;

import dev.abramenka.aggregation.model.AggregationPart;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class AggregationPartExecutionState {

    private final Set<String> appliedPartNames = new LinkedHashSet<>();

    List<String> missingDependencies(AggregationPart part) {
        return part.dependencies().stream()
                .filter(dependency -> !appliedPartNames.contains(dependency))
                .toList();
    }

    void markApplied(AggregationPart part) {
        appliedPartNames.add(part.name());
    }
}
