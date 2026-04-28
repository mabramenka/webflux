package dev.abramenka.aggregation.workflow.recursive;

import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Result of traversing one seed group.
 *
 * @param targetMetadata group metadata preserved from the corresponding seed group
 * @param resolvedNodes resolved terminal nodes in first-discovery order for this group
 */
public record TraversalGroupResult(@Nullable JsonNode targetMetadata, List<TraversalNode> resolvedNodes) {

    public TraversalGroupResult {
        Objects.requireNonNull(resolvedNodes, "resolvedNodes");
        if (resolvedNodes.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("resolvedNodes must not contain null items");
        }
        resolvedNodes = List.copyOf(resolvedNodes);
        targetMetadata = targetMetadata == null ? null : targetMetadata.deepCopy();
    }

    @Override
    public @Nullable JsonNode targetMetadata() {
        return targetMetadata == null ? null : targetMetadata.deepCopy();
    }

    ObjectNode toJsonNode(JsonNodeFactory nodeFactory) {
        ObjectNode out = nodeFactory.objectNode();
        ArrayNode resolved = out.putArray("resolvedNodes");
        for (TraversalNode node : resolvedNodes) {
            resolved.add(node.toJsonNode(nodeFactory));
        }
        out.set("targetMetadata", targetMetadata == null ? nodeFactory.nullNode() : targetMetadata.deepCopy());
        return out;
    }
}
