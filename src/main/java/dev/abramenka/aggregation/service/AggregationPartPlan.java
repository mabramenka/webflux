package dev.abramenka.aggregation.service;

import dev.abramenka.aggregation.enrichment.AggregationEnrichment;
import dev.abramenka.aggregation.model.AggregationPart;
import dev.abramenka.aggregation.model.AggregationPartSelection;
import dev.abramenka.aggregation.postprocessor.AggregationPostProcessor;
import java.util.List;

record AggregationPartPlan(
        AggregationPartSelection requestedSelection,
        AggregationPartSelection effectiveSelection,
        List<AggregationEnrichment> selectedEnrichments,
        List<AggregationPostProcessor> selectedPostProcessors,
        List<List<AggregationPart>> selectedLevels) {

    AggregationPartExecutionPlan executionPlan() {
        return new AggregationPartExecutionPlan(selectedLevels);
    }
}
