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

    List<AggregationEnrichment> supportedEnrichments(AggregationContext context) {
        return selectedEnrichments.stream()
                .filter(enrichment -> enrichment.supports(context))
                .toList();
    }

    List<AggregationPostProcessor> supportedPostProcessors(AggregationContext context) {
        return selectedPostProcessors.stream()
                .filter(postProcessor -> postProcessor.supports(context))
                .toList();
    }
}
