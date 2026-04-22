package dev.abramenka.aggregation.part.execution;

import dev.abramenka.aggregation.model.AggregationContext;
import dev.abramenka.aggregation.model.AggregationPart;
import dev.abramenka.aggregation.model.AggregationPartResult;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

@Component
@RequiredArgsConstructor
@Slf4j
public class AggregationPartExecutor {

    private final AggregationPartRunner partRunner;
    private final AggregationMerger aggregationMerger;
    private final AggregationPartResultApplicator resultApplicator;

    public Mono<JsonNode> execute(
            String rootClientName,
            JsonNode accountGroupResponse,
            AggregationContext context,
            AggregationPartPlan partPlan) {
        AggregationPartExecutionPlan executionPlan = partPlan.executionPlan();
        ObjectNode root = aggregationMerger.mutableRoot(rootClientName, accountGroupResponse);
        AggregationPartExecutionState executionState = new AggregationPartExecutionState();
        return Flux.fromIterable(executionPlan.levels())
                .concatMap(level -> runLevel(level, root, context, executionState))
                .then(Mono.just(root));
    }

    private Mono<Void> runLevel(
            List<AggregationPart> level,
            ObjectNode root,
            AggregationContext context,
            AggregationPartExecutionState executionState) {
        ObjectNode rootSnapshot = root.deepCopy();
        AggregationContext levelContext = context.withAccountGroupResponse(rootSnapshot);
        List<AggregationPart> supportedLevel = level.stream()
                .filter(part -> dependenciesApplied(part, executionState))
                .filter(part -> part.supports(levelContext))
                .toList();
        if (supportedLevel.isEmpty()) {
            return Mono.empty();
        }
        int concurrency = Math.max(1, supportedLevel.size());
        return Flux.fromIterable(supportedLevel)
                .flatMap(part -> partRunner.execute(part, rootSnapshot, levelContext), concurrency)
                .collectMap(AggregationPartResult::partName)
                .doOnNext(resultsByName -> applyResults(supportedLevel, resultsByName, root, executionState))
                .then();
    }

    private void applyResults(
            List<AggregationPart> level,
            Map<String, AggregationPartResult> resultsByName,
            ObjectNode root,
            AggregationPartExecutionState executionState) {
        for (AggregationPart part : level) {
            AggregationPartResult result = resultsByName.get(part.name());
            if (result != null) {
                try {
                    resultApplicator.apply(result, root);
                    executionState.markApplied(part);
                } catch (Exception ex) {
                    log.warn(
                            "Optional aggregation part '{}' failed during result merge and will be skipped",
                            part.name(),
                            ex);
                }
            }
        }
    }

    private boolean dependenciesApplied(AggregationPart part, AggregationPartExecutionState executionState) {
        List<String> missingDependencies = executionState.missingDependencies(part);
        if (missingDependencies.isEmpty()) {
            return true;
        }
        log.warn(
                "Optional aggregation part '{}' will be skipped because dependency result(s) are missing: {}",
                part.name(),
                String.join(", ", missingDependencies));
        return false;
    }
}
