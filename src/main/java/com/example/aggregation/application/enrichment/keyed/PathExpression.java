package com.example.aggregation.application.enrichment.keyed;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import tools.jackson.databind.JsonNode;

record PathExpression(List<PathSegment> segments) {

    static PathExpression parse(String rawPath) {
        Objects.requireNonNull(rawPath, "rawPath");
        String normalized = rawPath.strip();
        if (normalized.equals("$")) {
            return new PathExpression(List.of());
        }
        if (normalized.startsWith("$.")) {
            normalized = normalized.substring(2);
        }
        if (normalized.isEmpty()) {
            return new PathExpression(List.of());
        }

        List<PathSegment> segments = new ArrayList<>();
        for (String rawSegment : normalized.split("\\.")) {
            segments.add(PathSegment.parse(rawSegment, rawPath));
        }
        return new PathExpression(List.copyOf(segments));
    }

    List<JsonNode> select(JsonNode root) {
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
        return segments.get(segments.size() - 1).array();
    }

    String lastField() {
        return segments.get(segments.size() - 1).field();
    }

    PathExpression withoutLastSegment() {
        return new PathExpression(List.copyOf(segments.subList(0, segments.size() - 1)));
    }

    private static List<JsonNode> selectSegment(List<JsonNode> current, PathSegment segment) {
        List<JsonNode> selected = new ArrayList<>();
        current.stream()
            .map(node -> node.path(segment.field()))
            .forEach(node -> {
                if (segment.array()) {
                    if (node.isArray()) {
                        node.values().forEach(selected::add);
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
