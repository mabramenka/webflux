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

    final class ReplaceDocument implements AggregationPartResult {

        private final String partName;
        private final ObjectNode replacement;

        private ReplaceDocument(String partName, ObjectNode replacement) {
            this.partName = partName;
            this.replacement = replacement;
        }

        @Override
        public String partName() {
            return partName;
        }

        public ObjectNode replacement() {
            return replacement.deepCopy();
        }
    }

    final class MergePatch implements AggregationPartResult {

        private final String partName;
        private final ObjectNode base;
        private final ObjectNode replacement;

        private MergePatch(String partName, ObjectNode base, ObjectNode replacement) {
            this.partName = partName;
            this.base = base;
            this.replacement = replacement;
        }

        @Override
        public String partName() {
            return partName;
        }

        public ObjectNode base() {
            return base.deepCopy();
        }

        public ObjectNode replacement() {
            return replacement.deepCopy();
        }
    }
}
