package dev.abramenka.aggregation.workflow.compute;

import tools.jackson.databind.JsonNode;

/**
 * Pure computation step for a workflow. Implementations contain business logic only and must not:
 *
 * <ul>
 *   <li>Call downstream REST clients.
 *   <li>Mutate root JSON documents (ROOT_SNAPSHOT, CURRENT_ROOT, or the global root) directly.
 *   <li>Apply JSON patches directly.
 *   <li>Build or throw HTTP / RFC 9457 error bodies.
 *   <li>Perform recursive traversal.
 * </ul>
 *
 * <p>Inputs are declared via {@link ComputationInput} on the wrapping {@link
 * dev.abramenka.aggregation.workflow.step.ComputeStep} and resolved from the workflow state before
 * this method is called. The returned {@link JsonNode} is stored in the workflow variable store
 * under the step's configured result name, making it available to later steps as a
 * {@link dev.abramenka.aggregation.workflow.binding.KeySource#STEP_RESULT}.
 *
 * <p>To signal bad or missing input data, throw {@link ComputationException#inputViolation(String)};
 * this maps to {@code ENRICH-CONTRACT-VIOLATION}. Any other uncaught exception maps to
 * {@code ORCH-INVARIANT-VIOLATED}.
 */
@FunctionalInterface
public interface WorkflowComputation {

    JsonNode compute(WorkflowValues values);
}
