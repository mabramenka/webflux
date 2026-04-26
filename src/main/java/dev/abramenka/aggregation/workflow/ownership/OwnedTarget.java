package dev.abramenka.aggregation.workflow.ownership;

import java.util.Objects;

/**
 * Declares that a workflow part owns a particular root-document field. The field name is
 * coarse-grained (e.g. {@code "account1"}, {@code "riskScore"}) — not a full path expression —
 * so that two parts can quickly determine whether their write-sets overlap.
 */
public record OwnedTarget(String field) {

    public OwnedTarget {
        Objects.requireNonNull(field, "field");
        if (field.isBlank()) {
            throw new IllegalArgumentException("OwnedTarget field must not be blank");
        }
    }
}
