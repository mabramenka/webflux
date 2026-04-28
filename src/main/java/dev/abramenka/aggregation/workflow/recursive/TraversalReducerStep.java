package dev.abramenka.aggregation.workflow.recursive;

import dev.abramenka.aggregation.error.FacadeException;
import dev.abramenka.aggregation.error.OrchestrationException;
import dev.abramenka.aggregation.patch.JsonPatchDocument;
import dev.abramenka.aggregation.workflow.StepResult;
import dev.abramenka.aggregation.workflow.WorkflowContext;
import dev.abramenka.aggregation.workflow.WorkflowStep;
import java.util.Objects;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

/** Thin workflow-step adapter for traversal-result reduction and write-back patch creation. */
public final class TraversalReducerStep implements WorkflowStep {

    private final String name;
    private final String traversalResultName;
    private final TraversalReducer reducer;

    public TraversalReducerStep(String name, String traversalResultName, TraversalReducer reducer) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("TraversalReducerStep name must not be blank");
        }
        if (traversalResultName == null || traversalResultName.isBlank()) {
            throw new IllegalArgumentException("TraversalReducerStep traversalResultName must not be blank");
        }
        this.name = name;
        this.traversalResultName = traversalResultName;
        this.reducer = Objects.requireNonNull(reducer, "reducer");
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Mono<StepResult> execute(WorkflowContext context) {
        return Mono.fromCallable(() -> {
                    JsonNode traversalResult = context.variables()
                            .get(traversalResultName)
                            .orElseThrow(() -> OrchestrationException.invariantViolated(new IllegalStateException(
                                    "STEP_RESULT '" + traversalResultName + "' not found in workflow variables; "
                                            + "the producing step must have run and stored a value before this step")));
                    JsonPatchDocument patch =
                            Objects.requireNonNull(reducer.reduce(traversalResult), "reducer returned null patch");
                    return StepResult.applied(patch);
                })
                .onErrorMap(ex -> !(ex instanceof FacadeException), OrchestrationException::invariantViolated);
    }
}
