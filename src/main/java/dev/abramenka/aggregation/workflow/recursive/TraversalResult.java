package dev.abramenka.aggregation.workflow.recursive;

import java.util.List;
import java.util.Objects;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Immutable traversal output for future reducer and STEP_RESULT storage.
 *
 * @param groups ordered traversal groups with per-group metadata and resolved nodes
 */
public record TraversalResult(List<TraversalGroupResult> groups) {

    public TraversalResult {
        Objects.requireNonNull(groups, "groups");
        if (groups.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("groups must not contain null items");
        }
        groups = List.copyOf(groups);
    }

    public ObjectNode toJsonNode(JsonNodeFactory nodeFactory) {
        Objects.requireNonNull(nodeFactory, "nodeFactory");
        ObjectNode out = nodeFactory.objectNode();
        ArrayNode groupsNode = out.putArray("groups");
        for (TraversalGroupResult group : groups) {
            groupsNode.add(group.toJsonNode(nodeFactory));
        }
        return out;
    }
}
