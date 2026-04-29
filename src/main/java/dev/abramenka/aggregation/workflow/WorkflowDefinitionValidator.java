package dev.abramenka.aggregation.workflow;

import dev.abramenka.aggregation.workflow.ownership.WriteOwnership;
import java.util.HashSet;
import java.util.Set;

/**
 * Static validation applied to an {@link AggregationWorkflow} at construction (and therefore at
 * Spring bean creation), so invalid workflow definitions fail at startup rather than on the first
 * matching request.
 *
 * <p>Phase 6 enforces structural rules: blank names, duplicate step names.
 *
 * <p>Phase 13 adds write-ownership validation when {@link AggregationWorkflow#writeOwnership()} is
 * non-null:
 *
 * <ul>
 *   <li>Every step's declared {@link WorkflowStep#writtenFieldName()} must be listed in the
 *       ownership set — an undeclared write is a contract violation.
 *   <li>Two steps declaring the same written field is flagged as an obviously conflicting
 *       definition at construction time, before any runtime conflict detection is needed.
 * </ul>
 */
final class WorkflowDefinitionValidator {

    private WorkflowDefinitionValidator() {}

    public static void validate(AggregationWorkflow workflow) {
        validateStepNames(workflow);
        validateWriteOwnership(workflow);
    }

    private static void validateStepNames(AggregationWorkflow workflow) {
        Set<String> stepNames = new HashSet<>();
        for (WorkflowStep step : workflow.steps()) {
            String stepName = step.name();
            if (stepName.isBlank()) {
                throw new IllegalStateException("Workflow '" + workflow.name() + "' contains a step with a blank name");
            }
            if (!stepNames.add(stepName)) {
                throw new IllegalStateException(
                        "Workflow '" + workflow.name() + "' has duplicate step name: " + stepName);
            }
        }
    }

    private static void validateWriteOwnership(AggregationWorkflow workflow) {
        WriteOwnership ownership = workflow.writeOwnership();
        if (ownership == null) {
            return; // ownership is optional; no checks when not declared
        }
        Set<String> claimedFields = new HashSet<>();
        for (WorkflowStep step : workflow.steps()) {
            step.writtenFieldName().ifPresent(field -> {
                if (!ownership.contains(field)) {
                    throw new IllegalStateException("Workflow '" + workflow.name() + "': step '" + step.name()
                            + "' writes to field '" + field
                            + "' which is not declared in WriteOwnership");
                }
                if (!claimedFields.add(field)) {
                    throw new IllegalStateException("Workflow '" + workflow.name()
                            + "': two steps both write to field '" + field
                            + "' — obviously conflicting write definition");
                }
            });
        }
    }
}
