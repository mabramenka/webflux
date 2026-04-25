/**
 * Reusable services that turn {@link dev.abramenka.aggregation.workflow.binding.DownstreamBinding}
 * descriptors into runtime values. {@link
 * dev.abramenka.aggregation.workflow.binding.support.KeyExtractor} extracts keys (and their owning
 * items) from a chosen source, {@link
 * dev.abramenka.aggregation.workflow.binding.support.ResponseIndexer} indexes a downstream response
 * by one of its key paths, and {@link
 * dev.abramenka.aggregation.workflow.binding.support.TargetMatcher} joins extracted targets against
 * the indexed response.
 *
 * <p>All services delegate path/key parsing to the shared {@code PathExpression} and {@code
 * KeyPathGroups} so the limited project-specific path dialect remains the single source of truth.
 * Phase 5 only supports {@link dev.abramenka.aggregation.workflow.binding.KeySource#ROOT_SNAPSHOT};
 * other sources are explicitly rejected and added in later phases.
 */
@NullMarked
package dev.abramenka.aggregation.workflow.binding.support;

import org.jspecify.annotations.NullMarked;
