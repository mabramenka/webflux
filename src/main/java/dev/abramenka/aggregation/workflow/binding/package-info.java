/**
 * Workflow downstream-binding model. A {@link
 * dev.abramenka.aggregation.workflow.binding.DownstreamBinding} describes one REST dependency
 * inside an aggregation part — its key source, key extraction rule, downstream call, response
 * indexing, optional named step result, and optional write rule. Each binding owns its own key
 * extraction so different REST dependencies in the same part may pull different keys from the same
 * source document.
 */
@NullMarked
package dev.abramenka.aggregation.workflow.binding;

import org.jspecify.annotations.NullMarked;
