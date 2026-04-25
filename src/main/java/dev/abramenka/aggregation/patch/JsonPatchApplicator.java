package dev.abramenka.aggregation.patch;

import java.util.List;
import java.util.Objects;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Applies a {@link JsonPatchDocument} to a mutable {@link ObjectNode} root. Operations are applied
 * in order; any failure throws {@link JsonPatchException} and leaves the patch partially applied
 * (callers are expected to operate on a working copy and discard on failure).
 */
public final class JsonPatchApplicator {

    private static final String APPEND_TOKEN = "-";

    public void apply(JsonPatchDocument patch, ObjectNode root) {
        for (JsonPatchOperation operation : patch.operations()) {
            applyOne(operation, root);
        }
    }

    private void applyOne(JsonPatchOperation operation, ObjectNode root) {
        JsonPointer pointer = JsonPointer.parse(operation.path());
        if (pointer.isRoot()) {
            throw new JsonPatchException("Root pointer '' is not supported for operation " + operationName(operation));
        }
        JsonNode parent = navigate(root, pointer.parentSegments(), operation.path());
        String leaf = pointer.lastSegment();
        switch (operation) {
            case JsonPatchOperation.Add add -> applyAdd(parent, leaf, add.value(), add.path());
            case JsonPatchOperation.Replace replace -> applyReplace(parent, leaf, replace.value(), replace.path());
            case JsonPatchOperation.Test test -> applyTest(parent, leaf, test.value(), test.path());
        }
    }

    private static JsonNode navigate(ObjectNode root, List<String> segments, String path) {
        JsonNode current = root;
        for (String segment : segments) {
            if (current instanceof ObjectNode object) {
                JsonNode next = object.get(segment);
                if (next == null) {
                    throw new JsonPatchException("Missing parent at segment '" + segment + "' for path '" + path + "'");
                }
                current = next;
            } else if (current instanceof ArrayNode array) {
                int index = parseIndex(segment, path, array.size(), false);
                JsonNode next = array.get(index);
                if (next == null) {
                    throw new JsonPatchException("Missing parent at index " + index + " for path '" + path + "'");
                }
                current = next;
            } else {
                throw new JsonPatchException(
                        "Cannot navigate into non-container node at segment '" + segment + "' for path '" + path + "'");
            }
        }
        return current;
    }

    private static void applyAdd(JsonNode parent, String leaf, JsonNode value, String path) {
        if (parent instanceof ObjectNode object) {
            object.set(leaf, value.deepCopy());
            return;
        }
        if (parent instanceof ArrayNode array) {
            if (APPEND_TOKEN.equals(leaf)) {
                array.add(value.deepCopy());
                return;
            }
            int index = parseIndex(leaf, path, array.size(), true);
            array.insert(index, value.deepCopy());
            return;
        }
        throw new JsonPatchException("Cannot add into non-container parent for path '" + path + "'");
    }

    private static void applyReplace(JsonNode parent, String leaf, JsonNode value, String path) {
        if (parent instanceof ObjectNode object) {
            if (!object.has(leaf)) {
                throw new JsonPatchException("Cannot replace missing field at path '" + path + "'");
            }
            object.set(leaf, value.deepCopy());
            return;
        }
        if (parent instanceof ArrayNode array) {
            int index = parseIndex(leaf, path, array.size(), false);
            array.set(index, value.deepCopy());
            return;
        }
        throw new JsonPatchException("Cannot replace inside non-container parent for path '" + path + "'");
    }

    private static void applyTest(JsonNode parent, String leaf, JsonNode value, String path) {
        JsonNode actual;
        if (parent instanceof ObjectNode object) {
            actual = object.get(leaf);
        } else if (parent instanceof ArrayNode array) {
            int index = parseIndex(leaf, path, array.size(), false);
            actual = array.get(index);
        } else {
            throw new JsonPatchException("Cannot test inside non-container parent for path '" + path + "'");
        }
        if (!Objects.equals(actual, value)) {
            throw new JsonPatchException("Test operation failed at path '" + path + "'");
        }
    }

    private static int parseIndex(String segment, String path, int size, boolean allowAppendIndex) {
        int index;
        try {
            index = Integer.parseInt(segment);
        } catch (NumberFormatException ex) {
            throw new JsonPatchException("Invalid array index '" + segment + "' at path '" + path + "'", ex);
        }
        int max = allowAppendIndex ? size : size - 1;
        if (index < 0 || index > max) {
            throw new JsonPatchException("Array index " + index + " out of bounds at path '" + path + "'");
        }
        return index;
    }

    private static String operationName(JsonPatchOperation operation) {
        return switch (operation) {
            case JsonPatchOperation.Add ignored -> "add";
            case JsonPatchOperation.Replace ignored -> "replace";
            case JsonPatchOperation.Test ignored -> "test";
        };
    }
}
