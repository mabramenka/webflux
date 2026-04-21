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
    private final AggregationMerger aggregationMerger;

    Mono<JsonNode> execute(
            String rootClientName,
            JsonNode accountGroupResponse,
            AggregationContext context,
            AggregationPartPlan partPlan) {
        List<AggregationEnrichment> enabledEnrichments = partPlan.supportedEnrichments(context);
        ObjectNode root = aggregationMerger.mutableRoot(rootClientName, accountGroupResponse);
        int concurrency = Math.max(1, enabledEnrichments.size());
        return Flux.fromIterable(enabledEnrichments)
                .flatMap(enrichment -> enrichmentExecutor.fetch(enrichment, context), concurrency)
                .collectList()
                .map(results -> aggregationMerger.merge(root, enabledEnrichments, results))
                .flatMap(merged -> runPostProcessors(partPlan.selectedPostProcessors(), root, context)
                        .thenReturn(merged));
    }

    private static Mono<Void> runPostProcessors(
            List<AggregationPostProcessor> enabledPostProcessors, ObjectNode root, AggregationContext context) {
        if (enabledPostProcessors.isEmpty()) {
            return Mono.empty();
        }
        return Flux.fromIterable(enabledPostProcessors)
                .concatMap(postProcessor ->
                        postProcessor.apply(root, context).onErrorResume(Exception.class, ex -> Mono.empty()))
                .then();
    }
}
