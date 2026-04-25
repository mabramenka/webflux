package dev.abramenka.aggregation.workflow;

import dev.abramenka.aggregation.model.AggregationPartResult;
import dev.abramenka.aggregation.model.PartSkipReason;
import dev.abramenka.aggregation.patch.JsonPatchDocument;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Aggregate outcome of running one {@link AggregationWorkflow}. Either:
 *
 * <ul>
 *   <li>{@code patch} is non-null and the workflow's combined writes are applied; or
 *   <li>{@code softStatus} + {@code softReason} are set and the part takes a no-op outcome.
 * </ul>
 */
public record WorkflowResult(
        @Nullable JsonPatchDocument patch,
        @Nullable SoftStatus softStatus,
        @Nullable PartSkipReason softReason) {

    public WorkflowResult {
        boolean hasSoft = softStatus != null;
        boolean hasReason = softReason != null;
        if (hasSoft != hasReason) {
            throw new IllegalArgumentException("softStatus and softReason must be set together");
        }
        if (patch == null && !hasSoft) {
            throw new IllegalArgumentException("WorkflowResult must carry either a patch or a soft outcome");
        }
        if (patch != null && hasSoft) {
            throw new IllegalArgumentException("WorkflowResult cannot carry both a patch and a soft outcome");
        }
    }

    public static WorkflowResult applied(JsonPatchDocument patch) {
        return new WorkflowResult(Objects.requireNonNull(patch, "patch"), null, null);
    }

    public static WorkflowResult skipped(PartSkipReason reason) {
        return new WorkflowResult(null, SoftStatus.SKIPPED, reason);
    }

    public static WorkflowResult empty(PartSkipReason reason) {
        return new WorkflowResult(null, SoftStatus.EMPTY, reason);
    }

    public AggregationPartResult toPartResult(String partName) {
        if (patch != null) {
            return AggregationPartResult.jsonPatch(partName, patch);
        }
        PartSkipReason reason = Objects.requireNonNull(softReason, "softReason");
        return switch (Objects.requireNonNull(softStatus, "softStatus")) {
            case SKIPPED -> AggregationPartResult.skipped(partName, reason);
            case EMPTY -> AggregationPartResult.empty(partName, reason);
        };
    }

    public enum SoftStatus {
        SKIPPED,
        EMPTY
    }
}
