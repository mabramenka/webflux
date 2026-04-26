package dev.abramenka.aggregation.workflow;

import dev.abramenka.aggregation.error.OrchestrationException;
import dev.abramenka.aggregation.patch.JsonPatchException;
import dev.abramenka.aggregation.patch.JsonPatchOperation;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * Tracks patch operations by final path across multiple workflow steps and rejects unsafe writes
 * before they can corrupt the workflow-local working document.
 *
 * <p>Deterministically rejects:
 *
 * <ul>
 *   <li>Same final path written twice with different values.
 *   <li>Same final path written with different operation types (e.g., {@code add} then {@code
 *       replace}).
 * </ul>
 *
 * <p>Always permits:
 *
 * <ul>
 *   <li>Array-append operations whose paths end with {@code /-} — multiple appends to the same
 *       array are intentional and do not conflict.
 *   <li>Idempotent writes — same path, same operation type, same value.
 * </ul>
 *
 * <p>Missing-parent and failed-test conflicts are detected by {@link
 * dev.abramenka.aggregation.patch.JsonPatchApplicator} and mapped to {@code ORCH-MERGE-FAILED} by
 * the caller.
 *
 * <p>One instance is created per workflow execution and must not be shared across executions.
 */
final class WorkflowPatchConflictDetector {

    private static final String APPEND_SUFFIX = "/-";

    /** Tracks the first write seen at each non-append path. */
    private final Map<String, JsonPatchOperation> writtenPaths = new LinkedHashMap<>();

    /**
     * Checks {@code op} against the accumulated write history. On success the operation is
     * registered so future checks can detect conflicts against it. Throws {@link
     * OrchestrationException} on conflict.
     *
     * @throws OrchestrationException if the operation conflicts with a previously recorded write
     */
    void check(JsonPatchOperation op) {
        if (op.path().endsWith(APPEND_SUFFIX)) {
            // Array appends are always permitted — multiple appends to the same array are expected.
            return;
        }
        JsonPatchOperation previous = writtenPaths.get(op.path());
        if (previous == null) {
            writtenPaths.put(op.path(), op);
            return;
        }
        if (sameType(previous, op) && Objects.equals(value(previous), value(op))) {
            // Idempotent — same path, same op type, same value.  Allow without re-recording.
            return;
        }
        throw OrchestrationException.mergeFailed(new JsonPatchException(
                "Workflow patch conflict at path '" + op.path() + "': path was already written by an earlier step"));
    }

    private static boolean sameType(JsonPatchOperation a, JsonPatchOperation b) {
        return a.getClass().equals(b.getClass());
    }

    private static @Nullable JsonNode value(JsonPatchOperation op) {
        return switch (op) {
            case JsonPatchOperation.Add add -> add.value();
            case JsonPatchOperation.Replace replace -> replace.value();
            case JsonPatchOperation.Test test -> test.value();
        };
    }
}
