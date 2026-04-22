package dev.abramenka.aggregation.part;

import dev.abramenka.aggregation.error.DownstreamClientException;
import dev.abramenka.aggregation.error.FacadeException;
import dev.abramenka.aggregation.error.OrchestrationException;
import dev.abramenka.aggregation.model.AggregationContext;
import dev.abramenka.aggregation.model.AggregationPart;
import dev.abramenka.aggregation.model.AggregationPartPlan;
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
public class AggregationPartExecutor {

    private final AggregationPartRunner partRunner;
    private final AggregationRootFactory rootFactory;
    private final AggregationPartResultApplicator resultApplicator;
    private final AggregationPartMetrics metrics;

    public Mono<JsonNode> execute(
            String rootClientName,
            JsonNode accountGroupResponse,
            AggregationContext context,
            AggregationPartPlan partPlan) {
        ObjectNode root = rootFactory.mutableRoot(rootClientName, accountGroupResponse);
        AggregationPartExecutionState executionState = new AggregationPartExecutionState();
        return Flux.fromIterable(partPlan.selectedLevels())
                .concatMap(level -> runLevel(rootClientName, level, root, context, executionState))
                .then(Mono.just(root));
    }

    private Mono<Void> runLevel(
            String rootClientName,
            List<AggregationPart> level,
            ObjectNode root,
            AggregationContext context,
            AggregationPartExecutionState executionState) {
        ObjectNode rootSnapshot = root.deepCopy();
        AggregationContext levelContext = context.withAccountGroupResponse(rootSnapshot);
        List<AggregationPart> supportedLevel = level.stream()
                .filter(part -> requiredPartCanRun(rootClientName, part, levelContext, executionState))
                .toList();
        int concurrency = Math.max(1, supportedLevel.size());
        return Flux.fromIterable(supportedLevel)
                .flatMap(part -> partRunner.execute(part, rootSnapshot, levelContext), concurrency)
                .collectMap(AggregationPartResult::partName)
                .doOnNext(resultsByName -> applyResults(supportedLevel, resultsByName, root, executionState))
                .then();
    }

    private boolean requiredPartCanRun(
            String rootClientName,
            AggregationPart part,
            AggregationContext context,
            AggregationPartExecutionState executionState) {
        requireDependenciesApplied(part, executionState);
        boolean supported;
        try {
            supported = part.supports(context);
        } catch (FacadeException ex) {
            metrics.record(part.name(), "failure");
            throw ex;
        } catch (Exception ex) {
            metrics.record(part.name(), "failure");
            throw OrchestrationException.invariantViolated(ex);
        }
        if (!supported) {
            metrics.record(part.name(), "failure");
            throw DownstreamClientException.contractViolation(rootClientName);
        }
        return true;
    }

    private void applyResults(
            List<AggregationPart> level,
            Map<String, AggregationPartResult> resultsByName,
            ObjectNode root,
            AggregationPartExecutionState executionState) {
        requireOneResultPerPart(level, resultsByName);
        for (AggregationPart part : level) {
            AggregationPartResult result = resultsByName.get(part.name());
            if (result != null) {
                try {
                    resultApplicator.apply(result, root);
                    metrics.record(part.name(), "success");
                    executionState.markApplied(part);
                } catch (FacadeException ex) {
                    metrics.record(part.name(), "failure");
                    throw ex;
                } catch (Exception ex) {
                    metrics.record(part.name(), "failure");
                    throw OrchestrationException.mergeFailed(ex);
                }
            }
        }
    }

    private void requireOneResultPerPart(
            List<AggregationPart> level, Map<String, AggregationPartResult> resultsByName) {
        Set<String> expectedNames = new LinkedHashSet<>();
        level.stream().map(AggregationPart::name).forEach(expectedNames::add);
        Set<String> actualNames = new LinkedHashSet<>(resultsByName.keySet());
        if (actualNames.equals(expectedNames)) {
            return;
        }

        expectedNames.stream()
                .filter(name -> !actualNames.contains(name))
                .forEach(name -> metrics.record(name, "failure"));
        throw OrchestrationException.invariantViolated(
                new IllegalStateException("Aggregation part result names do not match selected parts"));
    }

    private void requireDependenciesApplied(AggregationPart part, AggregationPartExecutionState executionState) {
        List<String> missingDependencies = executionState.missingDependencies(part);
        if (missingDependencies.isEmpty()) {
            return;
        }
        log.warn(
                "Required aggregation part '{}' cannot run because dependency result(s) are missing: {}",
                part.name(),
                String.join(", ", missingDependencies));
        metrics.record(part.name(), "failure");
        throw OrchestrationException.invariantViolated(
                new IllegalStateException("Required aggregation part dependencies were not applied"));
    }
}
