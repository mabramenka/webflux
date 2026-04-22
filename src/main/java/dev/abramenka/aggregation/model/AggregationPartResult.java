package dev.abramenka.aggregation.model;

import tools.jackson.databind.node.ObjectNode;

public sealed interface AggregationPartResult
        permits AggregationPartResult.MergePatch, AggregationPartResult.ReplaceDocument {

    String partName();

    static AggregationPartResult replacement(String partName, ObjectNode replacement) {
        return new ReplaceDocument(partName, replacement.deepCopy());
    }

    static AggregationPartResult patch(String partName, ObjectNode base, ObjectNode replacement) {
        return new MergePatch(partName, base.deepCopy(), replacement.deepCopy());
    }

    record ReplaceDocument(String partName, ObjectNode replacement) implements AggregationPartResult {}

    record MergePatch(String partName, ObjectNode base, ObjectNode replacement) implements AggregationPartResult {}
}
