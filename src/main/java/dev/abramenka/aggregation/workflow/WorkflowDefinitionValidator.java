package dev.abramenka.aggregation.workflow;

import java.util.HashSet;
import java.util.Set;

/**
 * Static validation applied to an {@link AggregationWorkflow} at construction (and therefore at
 * Spring bean creation), so invalid workflow definitions fail at startup rather than on the first
 * matching request.
 *
 * <p>Phase 6 enforces the structural rules visible at the workflow level: blank names, duplicate
 * step names. Step-internal validation (path syntax, binding/output references) is enforced by the
 * step types themselves as those types arrive in later phases.
 */
public final class WorkflowDefinitionValidator {

    private WorkflowDefinitionValidator() {}

    public static void validate(AggregationWorkflow workflow) {
        Set<String> stepNames = new HashSet<>();
        for (WorkflowStep step : workflow.steps()) {
            String stepName = step.name();
            if (stepName == null || stepName.isBlank()) {
                throw new IllegalStateException("Workflow '" + workflow.name() + "' contains a step with a blank name");
            }
            if (!stepNames.add(stepName)) {
                throw new IllegalStateException(
                        "Workflow '" + workflow.name() + "' has duplicate step name: " + stepName);
            }
        }
    }
}
