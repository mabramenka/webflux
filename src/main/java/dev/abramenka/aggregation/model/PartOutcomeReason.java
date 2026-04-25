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
        return switch (reason) {
            case NO_KEYS_IN_MAIN -> NO_KEYS_IN_MAIN;
            case DOWNSTREAM_EMPTY -> DOWNSTREAM_EMPTY;
            case DOWNSTREAM_NOT_FOUND -> DOWNSTREAM_NOT_FOUND;
            case DEPENDENCY_EMPTY -> DEPENDENCY_EMPTY;
            case UNSUPPORTED_CONTEXT -> UNSUPPORTED_CONTEXT;
        };
    }
}
