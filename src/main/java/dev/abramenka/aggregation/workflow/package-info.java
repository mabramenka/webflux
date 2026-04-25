/**
 * Workflow as an implementation style for {@link
 * dev.abramenka.aggregation.model.AggregationPart}. {@link
 * dev.abramenka.aggregation.workflow.AggregationWorkflow} declaratively describes a business
 * enrichment as a sequence of {@link dev.abramenka.aggregation.workflow.WorkflowStep}s; {@link
 * dev.abramenka.aggregation.workflow.WorkflowAggregationPart} adapts a workflow to the existing
 * part contract so the planner and executor pick it up automatically.
 *
 * <p>Hand-written {@code AggregationPart} implementations and workflow-based parts coexist. Phase
 * 6 introduces the skeleton; concrete steps (keyed binding, compute, traversal) arrive in later
 * phases.
 */
@NullMarked
package dev.abramenka.aggregation.workflow;

import org.jspecify.annotations.NullMarked;
