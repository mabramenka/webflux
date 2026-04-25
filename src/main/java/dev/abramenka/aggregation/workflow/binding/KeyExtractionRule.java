package dev.abramenka.aggregation.workflow.binding;

import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Selects keys for a downstream binding from a chosen {@link KeySource}.
 *
 * <p>{@code keyPaths} are tried in declaration order, so later paths act as fallbacks when an item
 * does not carry the primary key. Path syntax follows the project-specific narrow dialect: {@code
 * $}, dot-separated field access, and {@code [*]} array expansion.
 */
public record KeyExtractionRule(
        KeySource source, @Nullable String stepResultName, String sourceItemPath, List<String> keyPaths) {

    public KeyExtractionRule {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(sourceItemPath, "sourceItemPath");
        Objects.requireNonNull(keyPaths, "keyPaths");
        if (sourceItemPath.isBlank()) {
            throw new IllegalArgumentException("sourceItemPath must not be blank");
        }
        if (keyPaths.isEmpty()) {
            throw new IllegalArgumentException("keyPaths must not be empty");
        }
        for (String path : keyPaths) {
            if (path == null || path.isBlank()) {
                throw new IllegalArgumentException("keyPaths must not contain blank entries");
            }
        }
        if (source == KeySource.STEP_RESULT) {
            if (stepResultName == null || stepResultName.isBlank()) {
                throw new IllegalArgumentException("stepResultName is required when source is STEP_RESULT");
            }
        } else if (stepResultName != null) {
            throw new IllegalArgumentException("stepResultName is only valid when source is STEP_RESULT");
        }
        keyPaths = List.copyOf(keyPaths);
    }
}
