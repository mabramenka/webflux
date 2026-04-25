package dev.abramenka.aggregation.workflow;

import reactor.core.publisher.Mono;

/**
 * One technical operation inside an {@link AggregationWorkflow} (extract keys, fetch JSON, index
 * response, compute value, reduce traversal, write patch). Concrete step implementations arrive in
 * later phases (Phase 7 keyed binding, Phase 12 compute, Phase 14/15 traversal).
 */
public interface WorkflowStep {

    /** Identifier unique within an {@link AggregationWorkflow}; used for diagnostics and step-result lookup. */
    String name();

    Mono<StepResult> execute(WorkflowContext context);
}
