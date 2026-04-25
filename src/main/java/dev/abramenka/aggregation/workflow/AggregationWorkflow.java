package dev.abramenka.aggregation.workflow;

import dev.abramenka.aggregation.model.PartCriticality;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Declarative description of one workflow-based aggregation part: its part name, the {@link
 * PartCriticality}, the names of other parts it depends on, and the ordered list of {@link
 * WorkflowStep}s the executor walks at request time.
 */
public record AggregationWorkflow(
        String name, Set<String> dependencies, PartCriticality criticality, List<WorkflowStep> steps) {

    public AggregationWorkflow {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(dependencies, "dependencies");
        Objects.requireNonNull(criticality, "criticality");
        Objects.requireNonNull(steps, "steps");
        if (name.isBlank()) {
            throw new IllegalArgumentException("Workflow name must not be blank");
        }
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("Workflow '" + name + "' must declare at least one step");
        }
        dependencies = Set.copyOf(dependencies);
        steps = List.copyOf(steps);
    }
}
