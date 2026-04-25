package dev.abramenka.aggregation.workflow;

import dev.abramenka.aggregation.model.AggregationContext;
import java.util.Objects;
import tools.jackson.databind.node.ObjectNode;

/**
 * Per-workflow execution context. Wraps the surrounding {@link AggregationContext}, holds the
 * immutable root snapshot visible to this part at workflow start, and exposes the {@link
 * WorkflowVariableStore} that named step results write into.
 */
public final class WorkflowContext {

    private final AggregationContext aggregationContext;
    private final ObjectNode rootSnapshot;
    private final WorkflowVariableStore variables;

    public WorkflowContext(AggregationContext aggregationContext, ObjectNode rootSnapshot) {
        this.aggregationContext = Objects.requireNonNull(aggregationContext, "aggregationContext");
        this.rootSnapshot = Objects.requireNonNull(rootSnapshot, "rootSnapshot");
        this.variables = new WorkflowVariableStore();
    }

    public AggregationContext aggregationContext() {
        return aggregationContext;
    }

    /** Immutable root snapshot visible to the workflow at start. */
    public ObjectNode rootSnapshot() {
        return rootSnapshot;
    }

    public WorkflowVariableStore variables() {
        return variables;
    }
}
