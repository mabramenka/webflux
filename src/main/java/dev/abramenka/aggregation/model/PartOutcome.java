package dev.abramenka.aggregation.model;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

public record PartOutcome(
        PartOutcomeStatus status, @Nullable PartSkipReason reason) {

    public PartOutcome {
        Objects.requireNonNull(status, "status");
        if (status == PartOutcomeStatus.APPLIED && reason != null) {
            throw new IllegalArgumentException("APPLIED outcomes cannot carry a skip reason");
        }
        if (status != PartOutcomeStatus.APPLIED && reason == null) {
            throw new IllegalArgumentException("EMPTY/SKIPPED outcomes require a reason");
        }
    }

    public static PartOutcome applied() {
        return new PartOutcome(PartOutcomeStatus.APPLIED, null);
    }

    public static PartOutcome empty(PartSkipReason reason) {
        return new PartOutcome(PartOutcomeStatus.EMPTY, reason);
    }

    public static PartOutcome skipped(PartSkipReason reason) {
        return new PartOutcome(PartOutcomeStatus.SKIPPED, reason);
    }
}
