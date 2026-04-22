package dev.abramenka.aggregation.service;

import dev.abramenka.aggregation.model.AggregationContext;
import dev.abramenka.aggregation.model.AggregationPart;
import dev.abramenka.aggregation.model.AggregationPartResult;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
class AggregationPartExecutor {

    private final AggregationPartRunner partRunner;
    private final AggregationMerger aggregationMerger;

    Mono<JsonNode> execute(
            String rootClientName,
            JsonNode accountGroupResponse,
            AggregationContext context,
            AggregationPartPlan partPlan) {
        AggregationPartExecutionPlan executionPlan = partPlan.executionPlan();
        ObjectNode root = aggregationMerger.mutableRoot(rootClientName, accountGroupResponse);
        Set<String> appliedParts = new LinkedHashSet<>();
        return Flux.fromIterable(executionPlan.levels())
                .concatMap(level -> runLevel(level, root, context, appliedParts))
                .then(Mono.just(root));
    }

    private Mono<Void> runLevel(
            List<AggregationPart> level, ObjectNode root, AggregationContext context, Set<String> appliedParts) {
        ObjectNode rootSnapshot = root.deepCopy();
        AggregationContext levelContext = context.withAccountGroupResponse(rootSnapshot);
        List<AggregationPart> supportedLevel = level.stream()
                .filter(part -> dependenciesApplied(part, appliedParts))
                .filter(part -> part.supports(levelContext))
                .toList();
        if (supportedLevel.isEmpty()) {
            return Mono.empty();
        }
        int concurrency = Math.max(1, supportedLevel.size());
        return Flux.fromIterable(supportedLevel)
                .flatMap(part -> partRunner.execute(part, rootSnapshot, levelContext), concurrency)
                .collectMap(AggregationPartResult::partName)
                .doOnNext(resultsByName -> applyResults(supportedLevel, resultsByName, root, appliedParts))
                .then();
    }

    private void applyResults(
            List<AggregationPart> level,
            Map<String, AggregationPartResult> resultsByName,
            ObjectNode root,
            Set<String> appliedParts) {
        for (AggregationPart part : level) {
            AggregationPartResult result = resultsByName.get(part.name());
            if (result != null) {
                try {
                    result.applyTo(root);
                    appliedParts.add(part.name());
                } catch (Exception ex) {
                    log.warn(
                            "Optional aggregation part '{}' failed during result merge and will be skipped",
                            part.name(),
                            ex);
                }
            }
        }
    }

    private boolean dependenciesApplied(AggregationPart part, Set<String> appliedParts) {
        List<String> missingDependencies = part.dependencies().stream()
                .filter(dependency -> !appliedParts.contains(dependency))
                .toList();
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
