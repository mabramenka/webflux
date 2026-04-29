package dev.abramenka.aggregation.part;

import dev.abramenka.aggregation.api.AggregateRequest;
import dev.abramenka.aggregation.error.FacadeException;
import dev.abramenka.aggregation.error.OrchestrationException;
import dev.abramenka.aggregation.model.AggregationContext;
import dev.abramenka.aggregation.model.AggregationPart;
import dev.abramenka.aggregation.model.AggregationPartPlan;
import dev.abramenka.aggregation.model.AggregationPartResult;
import dev.abramenka.aggregation.model.AggregationResult;
import dev.abramenka.aggregation.model.ClientRequestContext;
import dev.abramenka.aggregation.model.PartOutcome;
import dev.abramenka.aggregation.model.PartOutcomeReason;
import dev.abramenka.aggregation.model.PartOutcomeStatus;
import dev.abramenka.aggregation.model.PartSkipReason;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.node.ObjectNode;

@Component
@RequiredArgsConstructor
@Slf4j
public class AggregationPartExecutor {

    private static final String OUTCOME_EMPTY = "empty";
    private static final String OUTCOME_FAILED = "failed";
    private static final String OUTCOME_FAILURE = "failure";
    private static final String OUTCOME_SKIPPED = "skipped";
    private static final String OUTCOME_SUCCESS = "success";

    private final AggregationPartRunner partRunner;
    private final AggregationPartFailurePolicy failurePolicy;
    private final AggregationPartResultApplicator resultApplicator;
    private final AggregationPartMetrics metrics;

    public Mono<AggregationResult> execute(
            ObjectNode root,
            ClientRequestContext clientRequestContext,
            AggregateRequest aggregateRequest,
            AggregationPartPlan partPlan) {
        AggregationPartExecutionState executionState = new AggregationPartExecutionState();
        Map<String, PartOutcome> outcomes = new LinkedHashMap<>();
        return Flux.fromIterable(partPlan.selectedLevels())
                .concatMap(level ->
                        runLevel(level, root, clientRequestContext, aggregateRequest, executionState, outcomes))
                .then(Mono.fromSupplier(() -> new AggregationResult(root, outcomes)));
    }

    private Mono<Void> runLevel(
            List<AggregationPart> level,
            ObjectNode root,
            ClientRequestContext clientRequestContext,
            AggregateRequest aggregateRequest,
            AggregationPartExecutionState executionState,
            Map<String, PartOutcome> outcomes) {
        ObjectNode rootSnapshot = root.deepCopy();
        AggregationContext levelContext = new AggregationContext(rootSnapshot, clientRequestContext, aggregateRequest);

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
                .flatMap(part -> executeWithPolicy(part, levelContext), concurrency)
                .collectMap(PartExecutionResult::partName)
                .doOnNext(resultsByName -> applyResults(toRun, resultsByName, root, executionState, outcomes))
                .then();
    }

    private Mono<PartExecutionResult> executeWithPolicy(AggregationPart part, AggregationContext levelContext) {
        return partRunner
                .execute(part, levelContext)
                .map(PartExecutionResult::success)
                .onErrorResume(error -> {
                    AggregationPartFailurePolicy.FailureDecision decision = failurePolicy.decide(part, error);
                    if (decision.failRequest()) {
                        Throwable decisionError = decision.error();
                        if (decisionError == null) {
                            return Mono.error(OrchestrationException.invariantViolated(new IllegalStateException(
                                    "FailureDecision with failRequest=true must include error")));
                        }
                        metrics.recordOutcome(part.name(), OUTCOME_FAILURE);
                        return Mono.error(decisionError);
                    }
                    PartOutcomeReason reason = decision.reason();
                    String errorCode = decision.errorCode();
                    if (reason == null || errorCode == null) {
                        return Mono.error(OrchestrationException.invariantViolated(new IllegalStateException(
                                "FailureDecision with failRequest=false must include reason and errorCode")));
                    }
                    return Mono.just(PartExecutionResult.failure(part.name(), reason, errorCode));
                });
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
            metrics.recordOutcome(part.name(), OUTCOME_FAILURE);
            throw ex;
        } catch (Exception ex) {
            metrics.recordOutcome(part.name(), OUTCOME_FAILURE);
            throw OrchestrationException.invariantViolated(ex);
        }
    }

    private void applyResults(
            List<AggregationPart> level,
            Map<String, PartExecutionResult> resultsByName,
            ObjectNode root,
            AggregationPartExecutionState executionState,
            Map<String, PartOutcome> outcomes) {
        requireOneResultPerPart(level, resultsByName);
        for (AggregationPart part : level) {
            PartExecutionResult executionResult = Objects.requireNonNull(resultsByName.get(part.name()));
            applyResult(part, executionResult, root, executionState, outcomes);
        }
    }

    private void applyResult(
            AggregationPart part,
            PartExecutionResult executionResult,
            ObjectNode root,
            AggregationPartExecutionState executionState,
            Map<String, PartOutcome> outcomes) {
        if (executionResult.failed()) {
            PartOutcomeReason reason = Objects.requireNonNull(executionResult.failureReason());
            String errorCode = Objects.requireNonNull(executionResult.errorCode());
            recordFailure(part, reason, errorCode, outcomes);
            return;
        }
        AggregationPartResult result = Objects.requireNonNull(executionResult.result());
        if (result instanceof AggregationPartResult.NoOp noOp) {
            recordNoOp(part, noOp, outcomes);
            return;
        }
        try {
            resultApplicator.apply(result, root);
        } catch (FacadeException ex) {
            metrics.recordOutcome(part.name(), OUTCOME_FAILURE);
            throw ex;
        } catch (Exception ex) {
            metrics.recordOutcome(part.name(), OUTCOME_FAILURE);
            throw OrchestrationException.mergeFailed(ex);
        }
        metrics.recordOutcome(part.name(), OUTCOME_SUCCESS);
        executionState.markApplied(part);
        putOutcome(part, PartOutcome.applied(part.criticality()), outcomes);
    }

    private void recordSkip(AggregationPart part, PartSkipReason reason, Map<String, PartOutcome> outcomes) {
        metrics.recordOutcome(part.name(), OUTCOME_SKIPPED);
        putOutcome(part, PartOutcome.skipped(part.criticality(), reason), outcomes);
    }

    private void recordNoOp(AggregationPart part, AggregationPartResult.NoOp noOp, Map<String, PartOutcome> outcomes) {
        PartOutcome outcome = noOp.status() == PartOutcomeStatus.EMPTY
                ? PartOutcome.empty(part.criticality(), noOp.reason())
                : PartOutcome.skipped(part.criticality(), noOp.reason());
        metrics.recordOutcome(part.name(), noOp.status() == PartOutcomeStatus.EMPTY ? OUTCOME_EMPTY : OUTCOME_SKIPPED);
        putOutcome(part, outcome, outcomes);
    }

    private void recordFailure(
            AggregationPart part, PartOutcomeReason reason, String errorCode, Map<String, PartOutcome> outcomes) {
        metrics.recordOutcome(part.name(), OUTCOME_FAILED);
        putOutcome(part, PartOutcome.failed(part.criticality(), reason, errorCode), outcomes);
    }

    private static void putOutcome(AggregationPart part, PartOutcome outcome, Map<String, PartOutcome> outcomes) {
        if (part.publicSelectable()) {
            outcomes.put(part.name(), outcome);
        }
    }

    private void requireOneResultPerPart(List<AggregationPart> level, Map<String, PartExecutionResult> resultsByName) {
        for (AggregationPart part : level) {
            if (!resultsByName.containsKey(part.name())) {
                metrics.recordOutcome(part.name(), OUTCOME_FAILURE);
                throw OrchestrationException.invariantViolated(
                        new IllegalStateException("Aggregation part '" + part.name() + "' did not emit a result"));
            }
        }
    }

    private record PartExecutionResult(
            String partName,
            @Nullable AggregationPartResult result,
            @Nullable PartOutcomeReason failureReason,
            @Nullable String errorCode) {

        static PartExecutionResult success(AggregationPartResult result) {
            return new PartExecutionResult(result.partName(), result, null, null);
        }

        static PartExecutionResult failure(String partName, PartOutcomeReason reason, String errorCode) {
            return new PartExecutionResult(partName, null, reason, errorCode);
        }

        boolean failed() {
            return failureReason != null;
        }
    }
}
