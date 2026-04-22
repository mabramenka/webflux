package dev.abramenka.aggregation.part.execution;

import dev.abramenka.aggregation.model.AggregationContext;
import dev.abramenka.aggregation.model.AggregationPart;
import dev.abramenka.aggregation.model.AggregationPartResult;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
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

    public Mono<JsonNode> execute(
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
                    applyResult(result, root);
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

    private static void applyResult(AggregationPartResult result, ObjectNode root) {
        switch (result) {
            case AggregationPartResult.ReplaceDocument replacement -> replaceRoot(root, replacement.replacement());
            case AggregationPartResult.MergePatch patch -> applyPatch(patch.base(), patch.replacement(), root);
        }
    }

    private static void replaceRoot(ObjectNode root, ObjectNode replacement) {
        root.removeAll();
        root.setAll(replacement);
    }

    private static void applyPatch(ObjectNode base, ObjectNode replacement, ObjectNode target) {
        for (String propertyName : changedPropertyNames(base, replacement)) {
            @Nullable JsonNode baseValue = base.optional(propertyName).orElse(null);
            @Nullable
            JsonNode replacementValue = replacement.optional(propertyName).orElse(null);
            if (replacementValue == null) {
                target.remove(propertyName);
            } else if (baseValue instanceof ObjectNode baseObject
                    && replacementValue instanceof ObjectNode replacementObject
                    && target.optional(propertyName).orElse(null) instanceof ObjectNode targetObject) {
                applyPatch(baseObject, replacementObject, targetObject);
            } else {
                target.set(propertyName, replacementValue.deepCopy());
            }
        }
    }

    private static Set<String> changedPropertyNames(ObjectNode base, ObjectNode replacement) {
        Set<String> propertyNames = new LinkedHashSet<>(base.propertyNames());
        propertyNames.addAll(replacement.propertyNames());
        propertyNames.removeIf(
                propertyName -> valuesEqual(base.optional(propertyName), replacement.optional(propertyName)));
        return propertyNames;
    }

    private static boolean valuesEqual(Optional<JsonNode> left, Optional<JsonNode> right) {
        if (left.isEmpty() || right.isEmpty()) {
            return left.isEmpty() && right.isEmpty();
        }
        return left.orElseThrow().equals(right.orElseThrow());
    }
}
