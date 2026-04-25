package dev.abramenka.aggregation.workflow.binding;

import java.util.List;
import java.util.Objects;

/**
 * Indexes a downstream response into entries keyed by one of the {@code responseKeyPaths}, tried in
 * declaration order. Path syntax follows the project-specific narrow dialect.
 */
public record ResponseIndexingRule(String responseItemPath, List<String> responseKeyPaths) {

    public ResponseIndexingRule {
        Objects.requireNonNull(responseItemPath, "responseItemPath");
        Objects.requireNonNull(responseKeyPaths, "responseKeyPaths");
        if (responseItemPath.isBlank()) {
            throw new IllegalArgumentException("responseItemPath must not be blank");
        }
        if (responseKeyPaths.isEmpty()) {
            throw new IllegalArgumentException("responseKeyPaths must not be empty");
        }
        for (String path : responseKeyPaths) {
            if (path == null || path.isBlank()) {
                throw new IllegalArgumentException("responseKeyPaths must not contain blank entries");
            }
        }
        responseKeyPaths = List.copyOf(responseKeyPaths);
    }
}
