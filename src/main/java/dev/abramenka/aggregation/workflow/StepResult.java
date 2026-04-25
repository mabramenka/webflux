package dev.abramenka.aggregation.workflow;

import dev.abramenka.aggregation.model.PartSkipReason;
import dev.abramenka.aggregation.patch.JsonPatchDocument;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * Outcome of executing one {@link WorkflowStep}. A step may write a patch fragment, store a named
 * value for later steps, return a soft outcome that propagates as the workflow's overall outcome,
 * or any combination of the first two.
 */
public sealed interface StepResult {

    static StepResult applied(JsonPatchDocument patch) {
        return new Applied(patch, null, null);
    }

    static StepResult applied(JsonPatchDocument patch, String storeAs, JsonNode storedValue) {
        return new Applied(patch, storeAs, storedValue);
    }

    static StepResult stored(String storeAs, JsonNode storedValue) {
        return new Applied(null, storeAs, storedValue);
    }

    static StepResult skipped(PartSkipReason reason) {
        return new Skipped(reason);
    }

    static StepResult empty(PartSkipReason reason) {
        return new Empty(reason);
    }

    record Applied(
            @Nullable JsonPatchDocument patch,
            @Nullable String storeAs,
            @Nullable JsonNode storedValue) implements StepResult {

        public Applied {
            if (patch == null && storeAs == null) {
                throw new IllegalArgumentException("Applied step must produce a patch, a stored value, or both");
            }
            if ((storeAs == null) != (storedValue == null)) {
                throw new IllegalArgumentException("storeAs and storedValue must be set together");
            }
            if (storeAs != null && storeAs.isBlank()) {
                throw new IllegalArgumentException("storeAs must not be blank");
            }
        }
    }

    record Skipped(PartSkipReason reason) implements StepResult {

        public Skipped {
            Objects.requireNonNull(reason, "reason");
        }
    }

    record Empty(PartSkipReason reason) implements StepResult {

        public Empty {
            Objects.requireNonNull(reason, "reason");
        }
    }
}
