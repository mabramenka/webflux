package dev.abramenka.aggregation.service;

import dev.abramenka.aggregation.enrichment.AggregationEnrichment;
import dev.abramenka.aggregation.error.UnsupportedAggregationPartException;
import dev.abramenka.aggregation.model.AggregationPartSelection;
import dev.abramenka.aggregation.postprocessor.AggregationPostProcessor;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Component
class AggregationPartPlanner {

    private final AggregationPartGraph partGraph;

    AggregationPartPlanner(List<AggregationEnrichment> enrichments, List<AggregationPostProcessor> postProcessors) {
        this.partGraph = AggregationPartGraph.from(enrichments, postProcessors);
    }

    AggregationPartPlan plan(@Nullable List<String> include) {
        AggregationPartSelection requestedSelection = AggregationPartSelection.from(include);
        validateSelection(requestedSelection);
        AggregationPartSelection effectiveSelection = partGraph.expandDependencies(requestedSelection);
        return new AggregationPartPlan(
                requestedSelection,
                effectiveSelection,
                partGraph.selectedEnrichments(effectiveSelection),
                partGraph.selectedPostProcessors(effectiveSelection));
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
