package dev.abramenka.aggregation.workflow.recursive;

import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Immutable traversal output for future reducer and STEP_RESULT storage.
 *
 * @param resolvedNodes resolved nodes in first-resolution order
 * @param targetMetadata optional target metadata supplied by traversal caller/reducer input
 */
public record TraversalResult(
        List<TraversalNode> resolvedNodes, @Nullable JsonNode targetMetadata) {

    public TraversalResult(List<TraversalNode> resolvedNodes) {
        this(resolvedNodes, null);
    }

    public TraversalResult {
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

    public ObjectNode toJsonNode(JsonNodeFactory nodeFactory) {
        Objects.requireNonNull(nodeFactory, "nodeFactory");
        ObjectNode out = nodeFactory.objectNode();
        ArrayNode resolved = out.putArray("resolvedNodes");
        for (TraversalNode node : resolvedNodes) {
            resolved.add(node.toJsonNode(nodeFactory));
        }
        out.set("targetMetadata", targetMetadata == null ? nodeFactory.nullNode() : targetMetadata.deepCopy());
        return out;
    }
}
