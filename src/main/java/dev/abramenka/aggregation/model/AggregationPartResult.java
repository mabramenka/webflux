package dev.abramenka.aggregation.model;

import tools.jackson.databind.node.ObjectNode;

public sealed interface AggregationPartResult
        permits AggregationPartResult.MergePatch, AggregationPartResult.NoOp, AggregationPartResult.ReplaceDocument {

    String partName();

    CompositionSpec compositionSpec();

    static AggregationPartResult replacement(String partName, ObjectNode replacement) {
        return replacement(
                partName,
                replacement,
                CompositionSpec.root(CompositionSpec.MergeMode.REPLACE, CompositionSpec.ConflictPolicy.OVERWRITE));
    }

    static AggregationPartResult replacement(String partName, ObjectNode replacement, CompositionSpec compositionSpec) {
        return new ReplaceDocument(partName, replacement.deepCopy(), compositionSpec);
    }

    static AggregationPartResult patch(String partName, ObjectNode base, ObjectNode replacement) {
        return patch(
                partName,
                base,
                replacement,
                CompositionSpec.root(
                        CompositionSpec.MergeMode.MERGE_PATCH, CompositionSpec.ConflictPolicy.OVERWRITE));
    }

    static AggregationPartResult patch(
            String partName, ObjectNode base, ObjectNode replacement, CompositionSpec compositionSpec) {
        return new MergePatch(partName, base.deepCopy(), replacement.deepCopy(), compositionSpec);
    }

    static AggregationPartResult empty(String partName, PartSkipReason reason) {
        return new NoOp(partName, PartOutcomeStatus.EMPTY, reason);
    }

    static AggregationPartResult skipped(String partName, PartSkipReason reason) {
        return new NoOp(partName, PartOutcomeStatus.SKIPPED, reason);
    }

    final class ReplaceDocument implements AggregationPartResult {

        private final String partName;
        private final ObjectNode replacement;
        private final CompositionSpec compositionSpec;

        private ReplaceDocument(String partName, ObjectNode replacement, CompositionSpec compositionSpec) {
            this.partName = partName;
            this.replacement = replacement;
            this.compositionSpec = compositionSpec;
        }

        @Override
        public String partName() {
            return partName;
        }

        public ObjectNode replacement() {
            return replacement.deepCopy();
        }

        @Override
        public CompositionSpec compositionSpec() {
            return compositionSpec;
        }
    }

    final class MergePatch implements AggregationPartResult {

        private final String partName;
        private final ObjectNode base;
        private final ObjectNode replacement;
        private final CompositionSpec compositionSpec;

        private MergePatch(String partName, ObjectNode base, ObjectNode replacement, CompositionSpec compositionSpec) {
            this.partName = partName;
            this.base = base;
            this.replacement = replacement;
            this.compositionSpec = compositionSpec;
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

        @Override
        public CompositionSpec compositionSpec() {
            return compositionSpec;
        }
    }

    record NoOp(String partName, PartOutcomeStatus status, PartSkipReason reason) implements AggregationPartResult {

        @Override
        public CompositionSpec compositionSpec() {
            return CompositionSpec.root(
                    CompositionSpec.MergeMode.MERGE_PATCH, CompositionSpec.ConflictPolicy.OVERWRITE);
        }
    }
}
