package dev.abramenka.aggregation.service;

import dev.abramenka.aggregation.enrichment.AggregationEnrichment;
import dev.abramenka.aggregation.model.AggregationContext;
import dev.abramenka.aggregation.model.AggregationPartSelection;
import dev.abramenka.aggregation.postprocessor.AggregationPostProcessor;
import java.util.List;

record AggregationPartPlan(
        AggregationPartSelection requestedSelection,
        AggregationPartSelection effectiveSelection,
        List<AggregationEnrichment> selectedEnrichments,
        List<AggregationPostProcessor> selectedPostProcessors) {

    AggregationPartExecutionPlan executionPlan(AggregationContext context) {
        return new AggregationPartExecutionPlan(
                selectedEnrichments.stream()
                        .filter(enrichment -> enrichment.supports(context))
                        .toList(),
                selectedPostProcessors.stream()
                        .filter(postProcessor -> postProcessor.supports(context))
                        .toList());
    }
}
