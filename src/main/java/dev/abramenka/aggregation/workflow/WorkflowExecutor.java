package dev.abramenka.aggregation.workflow;

import dev.abramenka.aggregation.model.AggregationContext;
import dev.abramenka.aggregation.patch.JsonPatchDocument;
import dev.abramenka.aggregation.patch.JsonPatchOperation;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Executes the steps of an {@link AggregationWorkflow} sequentially against an {@link
 * AggregationContext}. The first step that returns a soft outcome short-circuits the workflow with
 * that outcome; otherwise the executor combines every applied patch into a single {@link
 * JsonPatchDocument} in declaration order.
 */
@Component
public class WorkflowExecutor {

    public Mono<WorkflowResult> execute(AggregationWorkflow workflow, AggregationContext context) {
        WorkflowContext workflowContext = new WorkflowContext(context, context.accountGroupResponse());
        return runSteps(workflow.steps(), workflowContext, 0, new ArrayList<>());
    }

    private Mono<WorkflowResult> runSteps(
            List<WorkflowStep> steps, WorkflowContext context, int index, List<JsonPatchOperation> accumulated) {
        if (index == steps.size()) {
            return Mono.just(WorkflowResult.applied(new JsonPatchDocument(accumulated)));
        }
        WorkflowStep step = steps.get(index);
        return step.execute(context).flatMap(result -> switch (result) {
            case StepResult.Applied applied -> {
                if (applied.storeAs() != null && applied.storedValue() != null) {
                    context.variables().put(applied.storeAs(), applied.storedValue());
                }
                if (applied.patch() != null) {
                    accumulated.addAll(applied.patch().operations());
                }
                yield runSteps(steps, context, index + 1, accumulated);
            }
            case StepResult.Skipped skipped -> Mono.just(WorkflowResult.skipped(skipped.reason()));
            case StepResult.Empty empty -> Mono.just(WorkflowResult.empty(empty.reason()));
        });
    }
}
