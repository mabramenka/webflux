package dev.abramenka.aggregation.workflow;

import dev.abramenka.aggregation.error.OrchestrationException;
import dev.abramenka.aggregation.model.AggregationContext;
import dev.abramenka.aggregation.model.PartSkipReason;
import dev.abramenka.aggregation.patch.JsonPatchApplicator;
import dev.abramenka.aggregation.patch.JsonPatchDocument;
import dev.abramenka.aggregation.patch.JsonPatchException;
import dev.abramenka.aggregation.patch.JsonPatchOperation;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

/**
 * Executes the steps of an {@link AggregationWorkflow} sequentially.
 *
 * <p>After each successful patch-producing step:
 *
 * <ol>
 *   <li>Each operation is conflict-checked by {@link WorkflowPatchConflictDetector} — rejects same
 *       path with different value/type across steps.
 *   <li>The step patch is applied to {@link WorkflowContext#currentRoot()} so later steps with
 *       {@link dev.abramenka.aggregation.workflow.binding.KeySource#CURRENT_ROOT} see accumulated
 *       writes.
 *   <li>The same operations are accumulated into the final combined {@link JsonPatchDocument}.
 * </ol>
 *
 * <p>The combined patch is returned as {@link WorkflowResult}. It is applied to the global root
 * exactly once by the existing flow — this executor never touches the global root.
 *
 * <p>Emits {@code aggregation.binding.requests} metrics per step via {@link WorkflowBindingMetrics}.
 */
@Component
@RequiredArgsConstructor
public class WorkflowExecutor {

    private static final JsonPatchApplicator PATCH_APPLICATOR = new JsonPatchApplicator();

    private final WorkflowBindingMetrics bindingMetrics;

    public Mono<WorkflowResult> execute(AggregationWorkflow workflow, AggregationContext context) {
        WorkflowContext workflowContext = new WorkflowContext(context, context.accountGroupResponse());
        WorkflowPatchConflictDetector conflictDetector = new WorkflowPatchConflictDetector();
        return runSteps(workflow.steps(), workflowContext, 0, new ArrayList<>(), workflow.name(), conflictDetector);
    }

    private Mono<WorkflowResult> runSteps(
            List<WorkflowStep> steps,
            WorkflowContext context,
            int index,
            List<JsonPatchOperation> accumulated,
            String workflowName,
            WorkflowPatchConflictDetector conflictDetector) {
        if (index == steps.size()) {
            return Mono.just(WorkflowResult.applied(new JsonPatchDocument(accumulated)));
        }
        WorkflowStep step = steps.get(index);
        String metricBindingTag = step.bindingName().orElse(step.name());
        return step.execute(context)
                .doOnNext(result -> bindingMetrics.record(workflowName, metricBindingTag, outcomeTag(result)))
                .doOnError(ex -> bindingMetrics.record(workflowName, metricBindingTag, "failed"))
                .flatMap(result -> switch (result) {
                    case StepResult.Applied(JsonPatchDocument patch, String storeAs, JsonNode storedValue) -> {
                        if (storeAs != null && storedValue != null) {
                            context.variables().put(storeAs, storedValue);
                        }
                        if (patch != null) {
                            applyAndAccumulate(patch, context, accumulated, conflictDetector);
                        }
                        yield runSteps(steps, context, index + 1, accumulated, workflowName, conflictDetector);
                    }
                    case StepResult.Skipped(PartSkipReason reason) -> Mono.just(WorkflowResult.skipped(reason));
                    case StepResult.Empty(PartSkipReason reason) -> Mono.just(WorkflowResult.empty(reason));
                });
    }

    /**
     * Conflict-checks each operation in {@code patch}, then applies the patch to
     * {@code context.currentRoot()}, then appends the operations to the combined patch list.
     * {@link JsonPatchException} from either the conflict detector or the applicator is wrapped as
     * {@code ORCH-MERGE-FAILED}.
     */
    private static void applyAndAccumulate(
            JsonPatchDocument patch,
            WorkflowContext context,
            List<JsonPatchOperation> accumulated,
            WorkflowPatchConflictDetector conflictDetector) {
        try {
            for (JsonPatchOperation op : patch.operations()) {
                conflictDetector.check(op);
            }
            PATCH_APPLICATOR.apply(patch, context.currentRoot());
        } catch (JsonPatchException ex) {
            throw OrchestrationException.mergeFailed(ex);
        }
        accumulated.addAll(patch.operations());
    }

    private static String outcomeTag(StepResult result) {
        return switch (result) {
            case StepResult.Applied ignored -> "success";
            case StepResult.Skipped ignored -> "skipped";
            case StepResult.Empty ignored -> "empty";
        };
    }
}
