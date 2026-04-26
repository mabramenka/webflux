package dev.abramenka.aggregation.workflow;

import dev.abramenka.aggregation.model.AggregationContext;
import dev.abramenka.aggregation.patch.JsonPatchDocument;
import dev.abramenka.aggregation.patch.JsonPatchOperation;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Executes the steps of an {@link AggregationWorkflow} sequentially against an {@link
 * AggregationContext}. The first step that returns a soft outcome short-circuits the workflow with
 * that outcome; otherwise the executor combines every applied patch into a single {@link
 * JsonPatchDocument} in declaration order.
 *
 * <p>Emits an {@code aggregation.binding.requests} counter via {@link WorkflowBindingMetrics} after
 * each step resolves, with {@code part}/{@code binding}/{@code outcome} tags. Errors are recorded
 * as {@code failed} before being re-propagated.
 */
@Component
@RequiredArgsConstructor
public class WorkflowExecutor {

    private final WorkflowBindingMetrics bindingMetrics;

    public Mono<WorkflowResult> execute(AggregationWorkflow workflow, AggregationContext context) {
        WorkflowContext workflowContext = new WorkflowContext(context, context.accountGroupResponse());
        return runSteps(workflow.steps(), workflowContext, 0, new ArrayList<>(), workflow.name());
    }

    private Mono<WorkflowResult> runSteps(
            List<WorkflowStep> steps,
            WorkflowContext context,
            int index,
            List<JsonPatchOperation> accumulated,
            String workflowName) {
        if (index == steps.size()) {
            return Mono.just(WorkflowResult.applied(new JsonPatchDocument(accumulated)));
        }
        WorkflowStep step = steps.get(index);
        String metricBindingTag = step.bindingName().orElse(step.name());
        return step.execute(context)
                .doOnNext(result -> bindingMetrics.record(workflowName, metricBindingTag, outcomeTag(result)))
                .doOnError(ex -> bindingMetrics.record(workflowName, metricBindingTag, "failed"))
                .flatMap(result -> switch (result) {
                    case StepResult.Applied applied -> {
                        if (applied.storeAs() != null && applied.storedValue() != null) {
                            context.variables().put(applied.storeAs(), applied.storedValue());
                        }
                        if (applied.patch() != null) {
                            accumulated.addAll(applied.patch().operations());
                        }
                        yield runSteps(steps, context, index + 1, accumulated, workflowName);
                    }
                    case StepResult.Skipped skipped -> Mono.just(WorkflowResult.skipped(skipped.reason()));
                    case StepResult.Empty empty -> Mono.just(WorkflowResult.empty(empty.reason()));
                });
    }

    private static String outcomeTag(StepResult result) {
        return switch (result) {
            case StepResult.Applied ignored -> "success";
            case StepResult.Skipped ignored -> "skipped";
            case StepResult.Empty ignored -> "empty";
        };
    }
}
