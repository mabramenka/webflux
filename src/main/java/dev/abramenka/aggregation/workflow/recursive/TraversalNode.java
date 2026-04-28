package dev.abramenka.aggregation.workflow.recursive;

import java.util.Objects;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * One resolved node from recursive traversal.
 *
 * @param number resolved node key
 * @param node raw downstream node payload
 * @param depth first depth at which the node was resolved
 */
public record TraversalNode(String number, JsonNode node, int depth) {

    public TraversalNode {
        Objects.requireNonNull(number, "number");
        Objects.requireNonNull(node, "node");
        if (number.isBlank()) {
            throw new IllegalArgumentException("number must not be blank");
        }
        if (depth <= 0) {
            throw new IllegalArgumentException("depth must be greater than zero");
        }
        node = node.deepCopy();
    }

    @Override
    public JsonNode node() {
        return node.deepCopy();
    }

    ObjectNode toJsonNode(JsonNodeFactory nodeFactory) {
        ObjectNode out = nodeFactory.objectNode();
        out.put("number", number);
        out.put("depth", depth);
        out.set("node", node.deepCopy());
        return out;
    }
}
