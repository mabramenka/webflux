package dev.abramenka.aggregation.workflow;

import dev.abramenka.aggregation.model.PartCriticality;
import dev.abramenka.aggregation.workflow.ownership.WriteOwnership;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Declarative description of one workflow-based aggregation part: its part name, the {@link
 * PartCriticality}, the names of other parts it depends on, the ordered list of {@link
 * WorkflowStep}s the executor walks at request time, and an optional {@link WriteOwnership}
 * declaration.
 *
 * <p>When {@link #writeOwnership()} is non-null, {@link WorkflowDefinitionValidator} verifies at
 * construction time that every step's declared write field is in the ownership set, and that no
 * two steps claim the same field.
 */
public record AggregationWorkflow(
        String name,
        Set<String> dependencies,
        PartCriticality criticality,
        List<WorkflowStep> steps,
        @Nullable WriteOwnership writeOwnership) {

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

    /** Backward-compatible constructor — no write ownership declared. */
    public AggregationWorkflow(
            String name, Set<String> dependencies, PartCriticality criticality, List<WorkflowStep> steps) {
        this(name, dependencies, criticality, steps, null);
    }
}
