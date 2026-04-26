package dev.abramenka.aggregation.workflow;

import dev.abramenka.aggregation.model.AggregationContext;
import java.util.Objects;
import tools.jackson.databind.node.ObjectNode;

/**
 * Per-workflow execution context. Holds:
 *
 * <ul>
 *   <li>{@link #rootSnapshot()} — immutable deep copy of the root document visible to the business
 *       part at workflow start; never mutated during execution.
 *   <li>{@link #currentRoot()} — workflow-local mutable working document, initially identical to
 *       {@code rootSnapshot}; {@link WorkflowExecutor} applies each step's patch to it so later
 *       steps with {@link dev.abramenka.aggregation.workflow.binding.KeySource#CURRENT_ROOT} see
 *       the accumulated result. Not the global root owned by
 *       {@link dev.abramenka.aggregation.part.AggregationPartExecutor}.
 *   <li>{@link #variables()} — named step results written by earlier steps and readable by later
 *       ones via {@link dev.abramenka.aggregation.workflow.binding.KeySource#STEP_RESULT}.
 * </ul>
 */
public final class WorkflowContext {

    private final AggregationContext aggregationContext;
    private final ObjectNode rootSnapshot;
    private final ObjectNode currentRoot;
    private final WorkflowVariableStore variables;

    public WorkflowContext(AggregationContext aggregationContext, ObjectNode rootSnapshot) {
        this.aggregationContext = Objects.requireNonNull(aggregationContext, "aggregationContext");
        // Deep copy ensures rootSnapshot is immutable regardless of external mutations, and
        // currentRoot can diverge independently as workflow steps accumulate patches.
        this.rootSnapshot = Objects.requireNonNull(rootSnapshot, "rootSnapshot").deepCopy();
        this.currentRoot = this.rootSnapshot.deepCopy();
        this.variables = new WorkflowVariableStore();
    }

    public AggregationContext aggregationContext() {
        return aggregationContext;
    }

    /** Immutable deep copy of the root document at workflow start — never changed during execution. */
    public ObjectNode rootSnapshot() {
        return rootSnapshot;
    }

    /**
     * Workflow-local mutable working document. Starts identical to {@link #rootSnapshot()}; {@link
     * WorkflowExecutor} applies each successful step patch to it so that steps with {@code
     * CURRENT_ROOT} key source see all previously accumulated writes.
     */
    public ObjectNode currentRoot() {
        return currentRoot;
    }

    public WorkflowVariableStore variables() {
        return variables;
    }
}
