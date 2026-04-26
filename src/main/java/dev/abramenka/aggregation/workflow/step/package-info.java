/**
 * Concrete {@link dev.abramenka.aggregation.workflow.WorkflowStep} implementations. Phase 7 adds
 * {@link dev.abramenka.aggregation.workflow.step.KeyedBindingStep}, which implements the simple
 * keyed enrichment pattern: read keys from ROOT_SNAPSHOT → call downstream → index response →
 * match targets → produce {@link dev.abramenka.aggregation.patch.JsonPatchDocument}.
 */
@NullMarked
package dev.abramenka.aggregation.workflow.step;

import org.jspecify.annotations.NullMarked;
