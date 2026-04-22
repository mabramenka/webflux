package dev.abramenka.aggregation.service;

import dev.abramenka.aggregation.enrichment.AggregationEnrichment;
import dev.abramenka.aggregation.model.AggregationContext;
import dev.abramenka.aggregation.postprocessor.AggregationPostProcessor;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

@Component
@RequiredArgsConstructor
class AggregationPartExecutor {

    private final EnrichmentExecutor enrichmentExecutor;
    private final PostProcessorExecutor postProcessorExecutor;
    private final AggregationMerger aggregationMerger;

    Mono<JsonNode> execute(
            String rootClientName,
            JsonNode accountGroupResponse,
            AggregationContext context,
            AggregationPartPlan partPlan) {
        AggregationPartExecutionPlan executionPlan = partPlan.executionPlan(context);
        ObjectNode root = aggregationMerger.mutableRoot(rootClientName, accountGroupResponse);
        return runEnrichmentPhase(executionPlan.enrichmentPhase(), root, context)
                .flatMap(mergedRoot -> runPostProcessorPhase(executionPlan.postProcessorPhase(), mergedRoot, context)
                        .thenReturn(mergedRoot));
    }

    private Mono<ObjectNode> runEnrichmentPhase(
            List<AggregationEnrichment> enabledEnrichments, ObjectNode root, AggregationContext context) {
        int concurrency = Math.max(1, enabledEnrichments.size());
        return Flux.fromIterable(enabledEnrichments)
                .flatMap(enrichment -> enrichmentExecutor.fetch(enrichment, context), concurrency)
                .collectList()
                .map(results -> aggregationMerger.merge(root, enabledEnrichments, results));
    }

    private Mono<Void> runPostProcessorPhase(
            List<AggregationPostProcessor> enabledPostProcessors, ObjectNode root, AggregationContext context) {
        if (enabledPostProcessors.isEmpty()) {
            return Mono.empty();
        }
        return Flux.fromIterable(enabledPostProcessors)
                .concatMap(postProcessor -> postProcessorExecutor.apply(postProcessor, root, context))
                .then();
    }
}
