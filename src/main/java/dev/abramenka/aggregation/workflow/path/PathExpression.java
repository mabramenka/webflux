package dev.abramenka.aggregation.workflow.path;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import tools.jackson.databind.JsonNode;

public record PathExpression(List<PathSegment> segments) {

    public static PathExpression parse(String rawPath) {
        Objects.requireNonNull(rawPath, "rawPath");
        String normalized = rawPath.strip();
        if (normalized.equals("$")) {
            return new PathExpression(List.of());
        }
        if (normalized.startsWith("$") && !normalized.startsWith("$.")) {
            throw new IllegalArgumentException("Unsupported path expression: " + rawPath);
        }
        if (normalized.startsWith("$.")) {
            normalized = normalized.substring(2);
        }
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Path must not be empty: " + rawPath);
        }

        List<PathSegment> segments = new ArrayList<>();
        for (String rawSegment : normalized.split("\\.", -1)) {
            segments.add(PathSegment.parse(rawSegment, rawPath));
        }
        return new PathExpression(List.copyOf(segments));
    }

    public List<JsonNode> select(JsonNode root) {
        List<JsonNode> current = List.of(root);
        for (PathSegment segment : segments) {
            current = selectSegment(current, segment);
            if (current.isEmpty()) {
                return List.of();
            }
        }
        return current;
    }

    boolean endsWithArray() {
        return segments.getLast().array();
    }

    String lastField() {
        return segments.getLast().field();
    }

    PathExpression withoutLastSegment() {
        return new PathExpression(List.copyOf(segments.subList(0, segments.size() - 1)));
    }

    /**
     * Returns the RFC 6901 JSON Pointer string for the item at {@code index} within the array
     * selected by this expression. Requires exactly one {@code [*]} segment; throws {@link
     * UnsupportedOperationException} otherwise. Used by {@code KeyedBindingStep} to build patch
     * operation paths without duplicating path-parsing logic.
     */
    public String toItemPointerAt(int index) {
        if (index < 0) {
            throw new IllegalArgumentException("index must be non-negative: " + index);
        }
        StringBuilder pointer = new StringBuilder();
        boolean usedIndex = false;
        for (PathSegment seg : segments) {
            pointer.append('/').append(escapePointerToken(seg.field()));
            if (seg.array()) {
                if (usedIndex) {
                    throw new UnsupportedOperationException(
                            "toItemPointerAt does not support paths with multiple [*] segments: " + this);
                }
                pointer.append('/').append(index);
                usedIndex = true;
            }
        }
        if (!usedIndex) {
            throw new UnsupportedOperationException(
                    "toItemPointerAt requires a path with at least one [*] segment: " + this);
        }
        return pointer.toString();
    }

    private static String escapePointerToken(String token) {
        if (token.indexOf('~') < 0 && token.indexOf('/') < 0) {
            return token;
        }
        return token.replace("~", "~0").replace("/", "~1");
    }

    private static List<JsonNode> selectSegment(List<JsonNode> current, PathSegment segment) {
        List<JsonNode> selected = new ArrayList<>();
        current.stream().map(node -> node.path(segment.field())).forEach(node -> {
            if (segment.array()) {
                if (node.isArray()) {
                    selected.addAll(node.values());
                }
            } else {
                selected.add(node);
            }
        });
        return selected;
    }

    private record PathSegment(String field, boolean array) {

        private static PathSegment parse(String rawSegment, String rawPath) {
            if (rawSegment.isBlank()) {
                throw new IllegalArgumentException("Path contains an empty segment: " + rawPath);
            }

            boolean array = rawSegment.endsWith("[*]");
            String field = array ? rawSegment.substring(0, rawSegment.length() - 3) : rawSegment;
            if (field.isBlank() || field.contains("[") || field.contains("]")) {
                throw new IllegalArgumentException("Unsupported path segment '" + rawSegment + "' in " + rawPath);
            }
            return new PathSegment(field, array);
        }
    }
}
