/**
 * Coarse-grained write ownership declarations for workflow parts (Phase 13). A {@link
 * dev.abramenka.aggregation.workflow.ownership.WriteOwnership} names the set of root-document
 * fields a workflow intends to write so that tooling, reviewers, and validators can reason about
 * which parts own which fields without reading step implementation code.
 *
 * <p>Ownership is field-oriented, not a second path DSL. An {@link
 * dev.abramenka.aggregation.workflow.ownership.OwnedTarget} carries a simple field name (e.g.
 * {@code "account1"}) rather than a full path expression.
 */
@NullMarked
package dev.abramenka.aggregation.workflow.ownership;

import org.jspecify.annotations.NullMarked;
