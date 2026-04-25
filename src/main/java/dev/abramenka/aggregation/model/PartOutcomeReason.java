package dev.abramenka.aggregation.model;

public enum PartOutcomeReason {
    NO_KEYS_IN_MAIN,
    DOWNSTREAM_EMPTY,
    DOWNSTREAM_NOT_FOUND,
    DEPENDENCY_EMPTY,
    UNSUPPORTED_CONTEXT,
    TIMEOUT,
    INVALID_PAYLOAD,
    BAD_RESPONSE,
    UNAVAILABLE,
    AUTH_FAILED,
    CONTRACT_VIOLATION,
    INTERNAL;

    public static PartOutcomeReason fromSkipReason(PartSkipReason reason) {
        return valueOf(reason.name());
    }

    public static PartOutcomeReason fromFailureReason(PartFailureReason reason) {
        return valueOf(reason.name());
    }
}
