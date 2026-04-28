package dev.abramenka.aggregation.workflow.recursive;

import java.util.Objects;

/**
 * Configuration for recursive traversal behavior.
 *
 * @param maxDepth maximum allowed traversal depth; must be greater than zero
 * @param cyclePolicy behavior for already visited nodes
 * @param requireAllRequestedKeys when true, every requested key must exist in a fetched response
 */
public record TraversalPolicy(int maxDepth, CyclePolicy cyclePolicy, boolean requireAllRequestedKeys) {

    public TraversalPolicy {
        if (maxDepth <= 0) {
            throw new IllegalArgumentException("maxDepth must be greater than zero");
        }
        Objects.requireNonNull(cyclePolicy, "cyclePolicy");
    }
}
