package dev.abramenka.aggregation.workflow.compute;

import dev.abramenka.aggregation.workflow.binding.KeySource;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Declares one named input that a {@link dev.abramenka.aggregation.workflow.step.ComputeStep}
 * should resolve from the workflow state and expose to the {@link WorkflowComputation} under
 * {@link #valueName()}.
 *
 * <p>The resolved value is looked up at step execution time and passed through {@link
 * WorkflowValues}. Supported sources are ROOT_SNAPSHOT, CURRENT_ROOT, and STEP_RESULT.
 * TRAVERSAL_STATE is rejected at construction.
 */
public record ComputationInput(
        String valueName, KeySource source, @Nullable String stepResultName) {

    public ComputationInput {
        Objects.requireNonNull(valueName, "valueName");
        Objects.requireNonNull(source, "source");
        if (valueName.isBlank()) {
            throw new IllegalArgumentException("ComputationInput valueName must not be blank");
        }
        if (source == KeySource.TRAVERSAL_STATE) {
            throw new IllegalArgumentException("TRAVERSAL_STATE is not supported in ComputationInput");
        }
        if (source == KeySource.STEP_RESULT) {
            if (stepResultName == null || stepResultName.isBlank()) {
                throw new IllegalArgumentException("stepResultName is required when source is STEP_RESULT");
            }
        } else if (stepResultName != null) {
            throw new IllegalArgumentException("stepResultName is only valid when source is STEP_RESULT");
        }
    }

    /** Convenience factory for a ROOT_SNAPSHOT input. */
    public static ComputationInput fromRootSnapshot(String valueName) {
        return new ComputationInput(valueName, KeySource.ROOT_SNAPSHOT, null);
    }

    /** Convenience factory for a CURRENT_ROOT input. */
    public static ComputationInput fromCurrentRoot(String valueName) {
        return new ComputationInput(valueName, KeySource.CURRENT_ROOT, null);
    }

    /** Convenience factory for a STEP_RESULT input. */
    public static ComputationInput fromStepResult(String valueName, String stepResultName) {
        return new ComputationInput(valueName, KeySource.STEP_RESULT, stepResultName);
    }
}
