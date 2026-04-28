package dev.abramenka.aggregation.workflow.compute;

import org.jspecify.annotations.Nullable;

/**
 * Signals a failure inside a {@link WorkflowComputation}. The {@code inputError} flag controls how
 * the failure is mapped to an RFC 9457 problem response:
 *
 * <ul>
 *   <li>{@code inputError = true} — the downstream payload violates the expected contract;
 *       maps to {@code ENRICH-CONTRACT-VIOLATION}.
 *   <li>{@code inputError = false} — a bug or invariant violation inside the computation;
 *       maps to {@code ORCH-INVARIANT-VIOLATED}.
 * </ul>
 *
 * <p>Computation classes should use the static factories rather than the constructor directly.
 */
public final class ComputationException extends RuntimeException {

    private final boolean inputError;

    private ComputationException(String message, boolean inputError, @Nullable Throwable cause) {
        super(message, cause);
        this.inputError = inputError;
    }

    /**
     * Signals that the input data (from a prior downstream response or workflow state) violates the
     * contract expected by this computation. Maps to {@code ENRICH-CONTRACT-VIOLATION}.
     */
    public static ComputationException inputViolation(String message) {
        return new ComputationException(message, true, null);
    }

    /**
     * Signals an invariant violation or unexpected state inside the computation logic itself. Maps
     * to {@code ORCH-INVARIANT-VIOLATED}.
     */
    public static ComputationException invariant(String message, @Nullable Throwable cause) {
        return new ComputationException(message, false, cause);
    }

    public boolean isInputError() {
        return inputError;
    }
}
