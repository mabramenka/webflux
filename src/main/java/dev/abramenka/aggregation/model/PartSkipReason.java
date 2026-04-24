package dev.abramenka.aggregation.model;

public enum PartSkipReason {
    NO_KEYS_IN_MAIN,
    DOWNSTREAM_EMPTY,
    DOWNSTREAM_NOT_FOUND,
    DEPENDENCY_EMPTY,
    UNSUPPORTED_CONTEXT
}
