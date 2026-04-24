package dev.abramenka.aggregation.part;

import dev.abramenka.aggregation.error.FacadeException;
import dev.abramenka.aggregation.error.OrchestrationException;
import dev.abramenka.aggregation.model.AggregationContext;
import dev.abramenka.aggregation.model.AggregationPart;
import dev.abramenka.aggregation.model.AggregationPartPlan;
import dev.abramenka.aggregation.model.AggregationPartResult;
import dev.abramenka.aggregation.model.AggregationResult;
import dev.abramenka.aggregation.model.PartOutcome;
import dev.abramenka.aggregation.model.PartOutcomeStatus;
import dev.abramenka.aggregation.model.PartSkipReason;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private final AggregationRootFactory rootFactory;
    private final AggregationPartResultApplicator resultApplicator;
    private final AggregationPartMetrics metrics;

    public Mono<AggregationResult> execute(
            String rootClientName,
            JsonNode accountGroupResponse,
            AggregationContext context,
            AggregationPartPlan partPlan) {
        ObjectNode root = rootFactory.mutableRoot(rootClientName, accountGroupResponse);
        AggregationPartExecutionState executionState = new AggregationPartExecutionState();
        Map<String, PartOutcome> outcomes = new LinkedHashMap<>();
        return Flux.fromIterable(partPlan.selectedLevels())
                .concatMap(level -> runLevel(level, root, context, executionState, outcomes))
                .then(Mono.fromSupplier(() -> new AggregationResult(root, outcomes)));
    }

    private Mono<Void> runLevel(
            List<AggregationPart> level,
            ObjectNode root,
            AggregationContext context,
            AggregationPartExecutionState executionState,
            Map<String, PartOutcome> outcomes) {
        ObjectNode rootSnapshot = root.deepCopy();
        AggregationContext levelContext = context.withAccountGroupResponse(rootSnapshot);

        List<AggregationPart> toRun = new ArrayList<>(level.size());
        for (AggregationPart part : level) {
            PartSkipReason skipReason = skipReasonFor(part, levelContext, executionState);
            if (skipReason != null) {
                recordSkip(part, skipReason, outcomes);
            } else {
                toRun.add(part);
            }
        }

        int concurrency = Math.max(1, toRun.size());
        return Flux.fromIterable(toRun)
                .flatMap(part -> partRunner.execute(part, rootSnapshot, levelContext), concurrency)
                .collectMap(AggregationPartResult::partName)
                .doOnNext(resultsByName -> applyResults(toRun, resultsByName, root, executionState, outcomes))
                .then();
    }

    private @Nullable PartSkipReason skipReasonFor(
            AggregationPart part, AggregationContext context, AggregationPartExecutionState executionState) {
        List<String> missingDependencies = executionState.missingDependencies(part);
        if (!missingDependencies.isEmpty()) {
            log.debug(
                    "Skipping aggregation part '{}' because dependency result(s) did not apply: {}",
                    part.name(),
                    String.join(", ", missingDependencies));
            return PartSkipReason.DEPENDENCY_EMPTY;
        }
        return supportsSafely(part, context) ? null : PartSkipReason.UNSUPPORTED_CONTEXT;
    }

    private boolean supportsSafely(AggregationPart part, AggregationContext context) {
        try {
            return part.supports(context);
        } catch (FacadeException ex) {
            metrics.record(part.name(), "failure");
            throw ex;
        } catch (Exception ex) {
            metrics.record(part.name(), "failure");
            throw OrchestrationException.invariantViolated(ex);
        }
    }

    private void applyResults(
            List<AggregationPart> level,
            Map<String, AggregationPartResult> resultsByName,
            ObjectNode root,
            AggregationPartExecutionState executionState,
            Map<String, PartOutcome> outcomes) {
        requireOneResultPerPart(level, resultsByName);
        for (AggregationPart part : level) {
            AggregationPartResult result = resultsByName.get(part.name());
            if (result == null) {
                continue;
            }
            if (result instanceof AggregationPartResult.NoOp noOp) {
                recordNoOp(part, noOp, outcomes);
                continue;
            }
            try {
                resultApplicator.apply(result, root);
            } catch (FacadeException ex) {
                metrics.record(part.name(), "failure");
                throw ex;
            } catch (Exception ex) {
                metrics.record(part.name(), "failure");
                throw OrchestrationException.mergeFailed(ex);
            }
            metrics.record(part.name(), "success");
            executionState.markApplied(part);
            outcomes.put(part.name(), PartOutcome.applied());
        }
    }

    private void recordSkip(AggregationPart part, PartSkipReason reason, Map<String, PartOutcome> outcomes) {
        metrics.record(part.name(), "skipped");
        outcomes.put(part.name(), PartOutcome.skipped(reason));
    }

    private void recordNoOp(AggregationPart part, AggregationPartResult.NoOp noOp, Map<String, PartOutcome> outcomes) {
        PartOutcome outcome = noOp.status() == PartOutcomeStatus.EMPTY
                ? PartOutcome.empty(noOp.reason())
                : PartOutcome.skipped(noOp.reason());
        metrics.record(part.name(), noOp.status() == PartOutcomeStatus.EMPTY ? "empty" : "skipped");
        outcomes.put(part.name(), outcome);
    }

    private void requireOneResultPerPart(
            List<AggregationPart> level, Map<String, AggregationPartResult> resultsByName) {
        for (AggregationPart part : level) {
            if (!resultsByName.containsKey(part.name())) {
                metrics.record(part.name(), "failure");
                throw OrchestrationException.invariantViolated(
                        new IllegalStateException("Aggregation part '" + part.name() + "' did not emit a result"));
            }
        }
    }
}
