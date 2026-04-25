package dev.abramenka.aggregation.model;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

public record PartOutcome(
        PartOutcomeStatus status,
        PartCriticality criticality,
        @Nullable PartOutcomeReason reason,
        @Nullable String errorCode) {

    public PartOutcome {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(criticality, "criticality");
        if (status == PartOutcomeStatus.APPLIED && (reason != null || errorCode != null)) {
            throw new IllegalArgumentException("APPLIED outcomes cannot carry reason details");
        }
        if ((status == PartOutcomeStatus.EMPTY || status == PartOutcomeStatus.SKIPPED)
                && (reason == null || errorCode != null)) {
            throw new IllegalArgumentException("EMPTY/SKIPPED outcomes require a skip reason only");
        }
        if (status == PartOutcomeStatus.FAILED && (reason == null || errorCode == null || errorCode.isBlank())) {
            throw new IllegalArgumentException("FAILED outcomes require failure reason and error code");
        }
    }

    public static PartOutcome applied(PartCriticality criticality) {
        return new PartOutcome(PartOutcomeStatus.APPLIED, criticality, null, null);
    }

    public static PartOutcome empty(PartCriticality criticality, PartSkipReason reason) {
        return new PartOutcome(PartOutcomeStatus.EMPTY, criticality, PartOutcomeReason.fromSkipReason(reason), null);
    }

    public static PartOutcome skipped(PartCriticality criticality, PartSkipReason reason) {
        return new PartOutcome(PartOutcomeStatus.SKIPPED, criticality, PartOutcomeReason.fromSkipReason(reason), null);
    }

    public static PartOutcome failed(PartCriticality criticality, PartOutcomeReason reason, String errorCode) {
        return new PartOutcome(PartOutcomeStatus.FAILED, criticality, reason, errorCode);
    }
}
