package dev.abramenka.aggregation.workflow.binding;

import dev.abramenka.aggregation.patch.JsonPatchDocument;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * Outcome of executing a {@link DownstreamBinding}.
 *
 * <p>{@code patch} is non-null only when the binding had a {@link WriteRule}. {@code stepValue} is
 * non-null only when the binding had a {@code storeAs} name. {@code reason} is set for non-{@link
 * BindingOutcome#SUCCESS} outcomes to identify why the binding skipped, was empty, or failed.
 */
record BindingResult(
        BindingName name,
        BindingOutcome outcome,
        @Nullable JsonPatchDocument patch,
        @Nullable JsonNode stepValue,
        @Nullable String reason) {

    public BindingResult {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(outcome, "outcome");
        if (outcome != BindingOutcome.SUCCESS && reason == null) {
            throw new IllegalArgumentException("reason is required for non-SUCCESS outcomes");
        }
    }

    public static BindingResult success(
            BindingName name, @Nullable JsonPatchDocument patch, @Nullable JsonNode stepValue) {
        if (patch == null && stepValue == null) {
            throw new IllegalArgumentException("Successful binding must produce a patch, a step value, or both");
        }
        return new BindingResult(name, BindingOutcome.SUCCESS, patch, stepValue, null);
    }

    public static BindingResult empty(BindingName name, String reason) {
        return new BindingResult(name, BindingOutcome.EMPTY, null, null, reason);
    }

    public static BindingResult skipped(BindingName name, String reason) {
        return new BindingResult(name, BindingOutcome.SKIPPED, null, null, reason);
    }

    public static BindingResult failed(BindingName name, String reason) {
        return new BindingResult(name, BindingOutcome.FAILED, null, null, reason);
    }
}
