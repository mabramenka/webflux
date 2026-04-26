package dev.abramenka.aggregation.workflow.step;

import dev.abramenka.aggregation.error.DownstreamClientException;
import dev.abramenka.aggregation.error.FacadeException;
import dev.abramenka.aggregation.error.OrchestrationException;
import dev.abramenka.aggregation.workflow.StepResult;
import dev.abramenka.aggregation.workflow.WorkflowContext;
import dev.abramenka.aggregation.workflow.WorkflowStep;
import dev.abramenka.aggregation.workflow.binding.KeySource;
import dev.abramenka.aggregation.workflow.compute.ComputationException;
import dev.abramenka.aggregation.workflow.compute.ComputationInput;
import dev.abramenka.aggregation.workflow.compute.ComputationResult;
import dev.abramenka.aggregation.workflow.compute.WorkflowComputation;
import dev.abramenka.aggregation.workflow.compute.WorkflowValues;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

/**
 * A {@link WorkflowStep} that executes a pure {@link WorkflowComputation} and stores the result as
 * a named workflow variable accessible to later steps via {@link KeySource#STEP_RESULT}.
 *
 * <p>Execution algorithm:
 *
 * <ol>
 *   <li>Resolve each declared {@link ComputationInput} from {@link WorkflowContext}.
 *   <li>Wrap resolved inputs into a {@link WorkflowValues} view.
 *   <li>Call {@link WorkflowComputation#compute(WorkflowValues)} synchronously.
 *   <li>Store the returned {@link JsonNode} under {@link #storeAs()} in the workflow variable store.
 *   <li>Return {@link StepResult#stored(String, JsonNode)}.
 * </ol>
 *
 * <p>Error mapping:
 *
 * <ul>
 *   <li>{@link ComputationException} with {@code inputError = true} →
 *       {@code ENRICH-CONTRACT-VIOLATION}
 *   <li>{@link ComputationException} with {@code inputError = false} or any other uncaught
 *       exception → {@code ORCH-INVARIANT-VIOLATED}
 * </ul>
 *
 * <p>The computation must not call downstream clients, mutate root documents, apply patches, or
 * build HTTP error bodies. Violations are a code contract issue, not enforced at runtime.
 */
public final class ComputeStep implements WorkflowStep {

    private final String name;
    private final String storeAs;
    private final List<ComputationInput> inputs;
    private final WorkflowComputation computation;

    /**
     * @throws IllegalArgumentException if {@code name} or {@code storeAs} is blank, {@code inputs}
     *     is null, or {@code computation} is null
     */
    public ComputeStep(String name, String storeAs, List<ComputationInput> inputs, WorkflowComputation computation) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("ComputeStep name must not be blank");
        }
        if (storeAs == null || storeAs.isBlank()) {
            throw new IllegalArgumentException("ComputeStep storeAs must not be blank");
        }
        Objects.requireNonNull(inputs, "inputs");
        Objects.requireNonNull(computation, "computation");
        this.name = name;
        this.storeAs = storeAs;
        this.inputs = List.copyOf(inputs);
        this.computation = computation;
    }

    @Override
    public String name() {
        return name;
    }

    /** Returns the workflow variable name under which the compute result is stored. */
    public String storeAs() {
        return storeAs;
    }

    @Override
    public Mono<StepResult> execute(WorkflowContext context) {
        WorkflowValues values;
        try {
            values = resolveInputs(context);
        } catch (FacadeException ex) {
            return Mono.error(ex);
        } catch (Exception ex) {
            return Mono.error(OrchestrationException.invariantViolated(ex));
        }

        return Mono.fromCallable(() -> runCompute(values))
                .map(result -> StepResult.stored(result.name(), result.value()))
                .onErrorMap(ex -> !(ex instanceof FacadeException), OrchestrationException::invariantViolated);
    }

    private ComputationResult runCompute(WorkflowValues values) {
        try {
            JsonNode result = computation.compute(values);
            return new ComputationResult(storeAs, result);
        } catch (ComputationException ex) {
            if (ex.isInputError()) {
                throw DownstreamClientException.contractViolation("compute:" + name);
            }
            throw OrchestrationException.invariantViolated(ex);
        }
    }

    private WorkflowValues resolveInputs(WorkflowContext context) {
        Map<String, JsonNode> resolved = new LinkedHashMap<>();
        for (ComputationInput input : inputs) {
            JsonNode value =
                    switch (input.source()) {
                        case ROOT_SNAPSHOT -> context.rootSnapshot();
                        case CURRENT_ROOT -> context.currentRoot();
                        case STEP_RESULT ->
                            context.variables()
                                    .get(Objects.requireNonNull(input.stepResultName(), "stepResultName"))
                                    .orElseThrow(() -> OrchestrationException.invariantViolated(
                                            new IllegalStateException("STEP_RESULT '" + input.stepResultName()
                                                    + "' not found in workflow variables; the producing step "
                                                    + "must have run and stored a value before this step")));
                        case TRAVERSAL_STATE ->
                            throw new IllegalStateException("TRAVERSAL_STATE is not supported in ComputeStep");
                    };
            resolved.put(input.valueName(), value);
        }
        return new WorkflowValues(resolved);
    }
}
