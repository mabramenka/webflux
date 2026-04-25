package dev.abramenka.aggregation.workflow.binding;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Describes how a binding's response is written into the working document.
 *
 * <p>{@code targetItemPath} selects the items to write into. When {@code matchBy} is set, the write
 * happens per-item by joining the main item's key against the indexed response. When {@code
 * matchBy} is null, the write applies to every selected target item directly.
 */
public record WriteRule(String targetItemPath, @Nullable MatchBy matchBy, WriteAction action) {

    public WriteRule {
        Objects.requireNonNull(targetItemPath, "targetItemPath");
        Objects.requireNonNull(action, "action");
        if (targetItemPath.isBlank()) {
            throw new IllegalArgumentException("targetItemPath must not be blank");
        }
    }

    public record MatchBy(String mainKeyPath, String responseKeyPath) {

        public MatchBy {
            Objects.requireNonNull(mainKeyPath, "mainKeyPath");
            Objects.requireNonNull(responseKeyPath, "responseKeyPath");
            if (mainKeyPath.isBlank() || responseKeyPath.isBlank()) {
                throw new IllegalArgumentException("MatchBy paths must not be blank");
            }
        }
    }

    public sealed interface WriteAction {

        String fieldName();

        record ReplaceField(String fieldName) implements WriteAction {
            public ReplaceField {
                Objects.requireNonNull(fieldName, "fieldName");
                if (fieldName.isBlank()) {
                    throw new IllegalArgumentException("fieldName must not be blank");
                }
            }
        }

        record AppendToArray(String fieldName) implements WriteAction {
            public AppendToArray {
                Objects.requireNonNull(fieldName, "fieldName");
                if (fieldName.isBlank()) {
                    throw new IllegalArgumentException("fieldName must not be blank");
                }
            }
        }
    }
}
