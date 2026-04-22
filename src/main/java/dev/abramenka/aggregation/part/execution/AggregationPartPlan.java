package dev.abramenka.aggregation.part.execution;

import dev.abramenka.aggregation.model.AggregationPart;
import dev.abramenka.aggregation.model.AggregationPartSelection;
import java.util.List;

public record AggregationPartPlan(
        AggregationPartSelection requestedSelection,
        AggregationPartSelection effectiveSelection,
        List<List<AggregationPart>> selectedLevels) {

    AggregationPartExecutionPlan executionPlan() {
        return new AggregationPartExecutionPlan(selectedLevels);
    }
}
