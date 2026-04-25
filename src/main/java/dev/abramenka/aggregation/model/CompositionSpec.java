package dev.abramenka.aggregation.model;

import java.util.Objects;

public record CompositionSpec(String targetPath, MergeMode mergeMode, ConflictPolicy conflictPolicy) {

    public CompositionSpec {
        Objects.requireNonNull(targetPath, "targetPath");
        Objects.requireNonNull(mergeMode, "mergeMode");
        Objects.requireNonNull(conflictPolicy, "conflictPolicy");
    }

    public static CompositionSpec root(MergeMode mergeMode, ConflictPolicy conflictPolicy) {
        return new CompositionSpec("/", mergeMode, conflictPolicy);
    }

    public enum MergeMode {
        REPLACE,
        MERGE_PATCH
    }

    public enum ConflictPolicy {
        OVERWRITE,
        FAIL_ON_CONFLICT
    }
}
