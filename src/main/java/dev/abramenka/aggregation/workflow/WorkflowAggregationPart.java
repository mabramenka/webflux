package dev.abramenka.aggregation.workflow;

import dev.abramenka.aggregation.model.AggregationContext;
import dev.abramenka.aggregation.model.AggregationPart;
import dev.abramenka.aggregation.model.AggregationPartResult;
import dev.abramenka.aggregation.model.PartCriticality;
import java.util.Objects;
import java.util.Set;
import reactor.core.publisher.Mono;

/**
 * Adapter from an {@link AggregationWorkflow} to the existing {@link AggregationPart} contract.
 * Subclasses register as Spring beans and the planner picks them up automatically alongside
 * hand-written parts.
 *
 * <p>The workflow definition is validated at construction so invalid definitions fail when the
 * Spring bean is created, not when the first matching request arrives.
 */
public abstract class WorkflowAggregationPart implements AggregationPart {

    private final AggregationWorkflow workflow;
    private final WorkflowExecutor workflowExecutor;

    protected WorkflowAggregationPart(AggregationWorkflow workflow, WorkflowExecutor workflowExecutor) {
        this.workflow = Objects.requireNonNull(workflow, "workflow");
        this.workflowExecutor = Objects.requireNonNull(workflowExecutor, "workflowExecutor");
        WorkflowDefinitionValidator.validate(workflow);
    }

    @Override
    public final String name() {
        return workflow.name();
    }

    @Override
    public final Set<String> dependencies() {
        return workflow.dependencies();
    }

    @Override
    public final PartCriticality criticality() {
        return workflow.criticality();
    }

    @Override
    public final Mono<AggregationPartResult> execute(AggregationContext context) {
        return workflowExecutor.execute(workflow, context).map(result -> result.toPartResult(workflow.name()));
    }
}
