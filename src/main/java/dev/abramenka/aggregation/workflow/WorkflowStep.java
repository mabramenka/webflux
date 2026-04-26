package dev.abramenka.aggregation.workflow;

import java.util.Optional;
import reactor.core.publisher.Mono;

/**
 * One technical operation inside an {@link AggregationWorkflow} (extract keys, fetch JSON, index
 * response, compute value, reduce traversal, write patch). Concrete step implementations arrive in
 * later phases (Phase 7 keyed binding, Phase 12 compute, Phase 14/15 traversal).
 */
public interface WorkflowStep {

    /** Identifier unique within an {@link AggregationWorkflow}; used for diagnostics and step-result lookup. */
    String name();

    /**
     * Returns the downstream binding name for metric tagging. Steps that wrap a {@link
     * dev.abramenka.aggregation.workflow.binding.DownstreamBinding} should override this to expose
     * the binding's declared name so metrics clearly identify the downstream dependency. Steps
     * without a named binding return empty, and the executor falls back to {@link #name()}.
     */
    default Optional<String> bindingName() {
        return Optional.empty();
    }

    /**
     * Returns the root-document field name this step writes (e.g. {@code "account1"}), if any.
     * Used by {@link dev.abramenka.aggregation.workflow.WorkflowDefinitionValidator} to validate
     * {@link dev.abramenka.aggregation.workflow.ownership.WriteOwnership} declarations. Steps that
     * do not write to the root return empty.
     */
    default Optional<String> writtenFieldName() {
        return Optional.empty();
    }

    Mono<StepResult> execute(WorkflowContext context);
}
