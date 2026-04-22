package dev.abramenka.aggregation.part.execution;

import dev.abramenka.aggregation.error.UnsupportedAggregationPartException;
import dev.abramenka.aggregation.model.AggregationPart;
import dev.abramenka.aggregation.model.AggregationPartSelection;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Component
public class AggregationPartPlanner {

    private final AggregationPartGraph partGraph;

    public AggregationPartPlanner(List<AggregationPart> parts) {
        this.partGraph = AggregationPartGraph.from(parts);
    }

    public AggregationPartPlan plan(@Nullable List<String> include) {
        AggregationPartSelection requestedSelection = AggregationPartSelection.from(include);
        validateSelection(requestedSelection);
        AggregationPartSelection effectiveSelection = partGraph.expandDependencies(requestedSelection);
        return new AggregationPartPlan(
                requestedSelection, effectiveSelection, partGraph.selectedLevels(effectiveSelection));
    }

    private void validateSelection(AggregationPartSelection partSelection) {
        if (partSelection.all()) {
            return;
        }

        List<String> unknownParts = partGraph.unknownNames(partSelection.names());

        if (!unknownParts.isEmpty()) {
            throw new UnsupportedAggregationPartException(unknownParts);
        }
    }
}
