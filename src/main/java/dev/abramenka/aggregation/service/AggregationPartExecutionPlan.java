package dev.abramenka.aggregation.service;

import dev.abramenka.aggregation.enrichment.AggregationEnrichment;
import dev.abramenka.aggregation.postprocessor.AggregationPostProcessor;
import java.util.List;

record AggregationPartExecutionPlan(
        List<AggregationEnrichment> enrichmentPhase, List<AggregationPostProcessor> postProcessorPhase) {}
