package dev.abramenka.aggregation.workflow.binding;

/**
 * Where a binding extracts its keys from.
 *
 * <ul>
 *   <li>{@link #ROOT_SNAPSHOT} — immutable root document visible to the part at start.
 *   <li>{@link #CURRENT_ROOT} — workflow-local working document for the current part.
 *   <li>{@link #STEP_RESULT} — named result produced by an earlier step or binding.
 *   <li>{@link #TRAVERSAL_STATE} — frontier of an in-progress recursive traversal.
 * </ul>
 *
 * <p>Only {@link #STEP_RESULT} requires {@link KeyExtractionRule#stepResultName()} to be set.
 */
public enum KeySource {
    ROOT_SNAPSHOT,
    CURRENT_ROOT,
    STEP_RESULT,
    TRAVERSAL_STATE
}
