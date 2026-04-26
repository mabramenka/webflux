package dev.abramenka.aggregation.workflow.compute;

import java.util.Objects;
import tools.jackson.databind.JsonNode;

/**
 * The named output produced by a {@link WorkflowComputation} execution. Wraps the computed {@link
 * JsonNode} alongside the step-result name under which it will be stored in the workflow variable
 * store.
 *
 * <p>Produced internally by {@link dev.abramenka.aggregation.workflow.step.ComputeStep} and
 * immediately converted to {@link dev.abramenka.aggregation.workflow.StepResult#stored(String,
 * JsonNode)}; not returned to computation authors.
 */
public record ComputationResult(String name, JsonNode value) {

    public ComputationResult {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(value, "value");
        if (name.isBlank()) {
            throw new IllegalArgumentException("ComputationResult name must not be blank");
        }
    }
}
