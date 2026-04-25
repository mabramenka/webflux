package dev.abramenka.aggregation.model;

public enum PartFailureReason {
    TIMEOUT,
    INVALID_PAYLOAD,
    BAD_RESPONSE,
    UNAVAILABLE,
    AUTH_FAILED,
    CONTRACT_VIOLATION,
    INTERNAL
}
