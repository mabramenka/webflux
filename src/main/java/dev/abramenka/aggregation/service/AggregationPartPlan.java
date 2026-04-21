package dev.abramenka.aggregation.service;

import dev.abramenka.aggregation.enrichment.AggregationEnrichment;
import dev.abramenka.aggregation.model.AggregationContext;
import dev.abramenka.aggregation.model.EnrichmentSelection;
import dev.abramenka.aggregation.postprocessor.AggregationPostProcessor;
import java.util.List;

record AggregationPartPlan(
        EnrichmentSelection requestedSelection,
        EnrichmentSelection effectiveSelection,
        List<AggregationEnrichment> selectedEnrichments,
        List<AggregationPostProcessor> selectedPostProcessors) {

    List<AggregationEnrichment> supportedEnrichments(AggregationContext context) {
        return selectedEnrichments.stream()
                .filter(enrichment -> enrichment.supports(context))
                .toList();
    }
}
