package dev.abramenka.aggregation.workflow.ownership;

import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Declares the set of root-document fields that a workflow part intends to write. Used for
 * reviewability, static validation, conflict checks, documentation, and tests.
 *
 * <p>Ownership is coarse-grained and field-oriented. Each {@link OwnedTarget} carries a simple
 * field name — not a full path expression.
 *
 * <p>When a workflow carries a {@code WriteOwnership}, {@link
 * dev.abramenka.aggregation.workflow.WorkflowDefinitionValidator} verifies at construction time
 * that every step's declared write field is listed here, and that no two steps claim to write the
 * same field.
 */
public record WriteOwnership(Set<OwnedTarget> targets) {

    public WriteOwnership {
        Objects.requireNonNull(targets, "targets");
        targets = Set.copyOf(targets);
    }

    /** Returns {@code true} when {@code fieldName} is listed as an owned target. */
    public boolean contains(String fieldName) {
        return targets.stream().anyMatch(t -> t.field().equals(fieldName));
    }

    /** Convenience factory — constructs ownership from a vararg list of field names. */
    public static WriteOwnership of(String... fields) {
        return new WriteOwnership(Arrays.stream(fields).map(OwnedTarget::new).collect(Collectors.toSet()));
    }
}
