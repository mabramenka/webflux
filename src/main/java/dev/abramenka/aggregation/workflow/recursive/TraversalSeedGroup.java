package dev.abramenka.aggregation.workflow.recursive;

import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * One independent traversal seed group.
 *
 * @param targetMetadata optional metadata carried with the group for later reducers
 * @param initialKeys starting keys for traversal within this group
 */
public record TraversalSeedGroup(@Nullable JsonNode targetMetadata, List<String> initialKeys) {

    public TraversalSeedGroup {
        Objects.requireNonNull(initialKeys, "initialKeys");
        if (initialKeys.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("initialKeys must not contain null items");
        }
        initialKeys = List.copyOf(initialKeys);
        targetMetadata = targetMetadata == null ? null : targetMetadata.deepCopy();
    }

    @Override
    public @Nullable JsonNode targetMetadata() {
        return targetMetadata == null ? null : targetMetadata.deepCopy();
    }
}
