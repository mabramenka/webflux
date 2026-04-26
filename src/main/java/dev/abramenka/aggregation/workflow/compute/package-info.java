/**
 * Pure computation abstractions for workflow steps (Phase 12). A {@link
 * dev.abramenka.aggregation.workflow.compute.WorkflowComputation} receives a read-only {@link
 * dev.abramenka.aggregation.workflow.compute.WorkflowValues} view of the current workflow state and
 * returns a {@link tools.jackson.databind.JsonNode} result. The computation is pure: it must not
 * call downstream clients, mutate root documents, apply patches, or build HTTP error bodies.
 *
 * <p>{@link dev.abramenka.aggregation.workflow.step.ComputeStep} bridges this interface into the
 * existing {@link dev.abramenka.aggregation.workflow.WorkflowStep} /
 * {@link dev.abramenka.aggregation.workflow.StepResult} model.
 */
@NullMarked
package dev.abramenka.aggregation.workflow.compute;

import org.jspecify.annotations.NullMarked;
