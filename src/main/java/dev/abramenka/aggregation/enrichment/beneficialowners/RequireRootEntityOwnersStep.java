package dev.abramenka.aggregation.enrichment.beneficialowners;

import dev.abramenka.aggregation.model.PartSkipReason;
import dev.abramenka.aggregation.workflow.StepResult;
import dev.abramenka.aggregation.workflow.WorkflowContext;
import dev.abramenka.aggregation.workflow.WorkflowStep;
import java.util.Objects;
import reactor.core.publisher.Mono;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * Precondition step that preserves legacy skip behavior: no root entity owners means
 * SKIPPED / NO_KEYS_IN_MAIN.
 */
final class RequireRootEntityOwnersStep implements WorkflowStep {

    private final String name;
    private final RootEntityTargets rootEntityTargets;
    private final String markerStoreAs;

    RequireRootEntityOwnersStep(String name, RootEntityTargets rootEntityTargets, String markerStoreAs) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("RequireRootEntityOwnersStep name must not be blank");
        }
        if (markerStoreAs == null || markerStoreAs.isBlank()) {
            throw new IllegalArgumentException("RequireRootEntityOwnersStep markerStoreAs must not be blank");
        }
        this.name = name;
        this.rootEntityTargets = Objects.requireNonNull(rootEntityTargets, "rootEntityTargets");
        this.markerStoreAs = markerStoreAs;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Mono<StepResult> execute(WorkflowContext context) {
        if (rootEntityTargets.collect(context.rootSnapshot()).isEmpty()) {
            return Mono.just(StepResult.skipped(PartSkipReason.NO_KEYS_IN_MAIN));
        }
        return Mono.just(StepResult.stored(markerStoreAs, JsonNodeFactory.instance.booleanNode(true)));
    }
}
