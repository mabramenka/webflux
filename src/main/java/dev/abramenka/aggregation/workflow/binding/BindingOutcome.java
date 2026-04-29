package dev.abramenka.aggregation.workflow.binding;

/**
 * Coarse outcome of executing a binding. Mapped to public {@code meta.parts} state by the
 * containing workflow part — bindings themselves are not exposed in the public API.
 */
enum BindingOutcome {
    SUCCESS,
    EMPTY,
    SKIPPED,
    FAILED
}
