package dev.abramenka.aggregation.enrichment.support.keyed;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import tools.jackson.databind.node.ObjectNode;

record KeyPathGroups(List<KeyPathGroup> groups) {

    static KeyPathGroups parse(String... keyPaths) {
        if (keyPaths.length == 0) {
            throw new IllegalArgumentException("At least one key path is required");
        }

        Map<PathExpression, List<String>> keyFieldsBySource = new LinkedHashMap<>();
        for (String keyPath : keyPaths) {
            KeyPath parsed = KeyPath.parse(keyPath);
            keyFieldsBySource
                    .computeIfAbsent(parsed.sourcePath(), ignored -> new ArrayList<>())
                    .add(parsed.keyField());
        }

        List<KeyPathGroup> groups = keyFieldsBySource.entrySet().stream()
                .map(entry -> new KeyPathGroup(entry.getKey(), List.copyOf(entry.getValue())))
                .toList();
        return new KeyPathGroups(groups);
    }

    Stream<String> keysFrom(ObjectNode root) {
        return groups.stream().flatMap(group -> group.keysFrom(root));
    }

    Optional<String> firstKey(ObjectNode root) {
        return groups.stream().flatMap(group -> group.keysFrom(root)).findFirst();
    }

    private record KeyPathGroup(PathExpression sourcePath, List<String> keyFields) {

        private Stream<String> keysFrom(ObjectNode root) {
            return sourcePath.select(root).stream()
                    .filter(ObjectNode.class::isInstance)
                    .map(ObjectNode.class::cast)
                    .flatMap(source -> firstKeyFieldValue(source).stream());
        }

        private Optional<String> firstKeyFieldValue(ObjectNode source) {
            for (String keyField : keyFields) {
                String key = source.path(keyField).asString("");
                if (!key.isBlank()) {
                    return Optional.of(key);
                }
            }
            return Optional.empty();
        }
    }

    private record KeyPath(PathExpression sourcePath, String keyField) {

        private static KeyPath parse(String rawPath) {
            PathExpression expression = PathExpression.parse(rawPath);
            if (expression.segments().isEmpty()) {
                throw new IllegalArgumentException("Key path must not be empty: " + rawPath);
            }
            if (expression.endsWithArray()) {
                throw new IllegalArgumentException("Key path must end with a scalar field: " + rawPath);
            }
            return new KeyPath(expression.withoutLastSegment(), expression.lastField());
        }
    }
}
