package dev.abramenka.aggregation.workflow.compute;

import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

/**
 * Read-only view of the workflow inputs resolved for one {@link WorkflowComputation} invocation.
 * Each entry corresponds to a {@link ComputationInput} declared on the enclosing {@link
 * dev.abramenka.aggregation.workflow.step.ComputeStep} and is keyed by {@link
 * ComputationInput#valueName()}.
 *
 * <p>Computation classes receive this as the sole argument to
 * {@link WorkflowComputation#compute(WorkflowValues)}. They must not retain a reference to it
 * beyond the duration of the compute call.
 */
public final class WorkflowValues {

    private final Map<String, JsonNode> resolved;

    public WorkflowValues(Map<String, JsonNode> resolved) {
        this.resolved = Map.copyOf(resolved);
    }

    /**
     * Returns the input value registered under {@code name}, or empty if the name was not declared
     * as a {@link ComputationInput} for this step.
     */
    public Optional<JsonNode> get(String name) {
        return Optional.ofNullable(resolved.get(name));
    }

    /**
     * Returns the input value registered under {@code name}.
     *
     * @throws ComputationException with {@link ComputationException#isInputError()} {@code = true}
     *     if the name is absent — the declaring step misconfigured its inputs or the producing step
     *     did not run.
     */
    public JsonNode require(String name) {
        return get(name)
                .orElseThrow(() -> ComputationException.inputViolation(
                        "Required computation input '" + name + "' is not present"));
    }
}
