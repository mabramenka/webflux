package dev.abramenka.aggregation.model;

import java.util.List;
import java.util.Objects;

public record AggregationPartPlan(
        AggregationPartSelection requestedSelection,
        AggregationPartSelection effectiveSelection,
        List<List<AggregationPart>> selectedLevels) {

    public AggregationPartPlan {
        Objects.requireNonNull(requestedSelection, "requestedSelection");
        Objects.requireNonNull(effectiveSelection, "effectiveSelection");
        selectedLevels = copyLevels(selectedLevels);
    }

    private static List<List<AggregationPart>> copyLevels(List<List<AggregationPart>> levels) {
        return Objects.requireNonNull(levels, "levels").stream()
                .map(List::copyOf)
                .toList();
    }
}
