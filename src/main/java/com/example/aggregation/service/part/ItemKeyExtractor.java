package com.example.aggregation.service.part;

import com.example.aggregation.service.part.KeyedArrayEnrichmentPart.EnrichmentTarget;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

final class ItemKeyExtractor {

    private final PathExpression itemExpression;
    private final KeyPathGroups keyGroups;

    private ItemKeyExtractor(PathExpression itemExpression, KeyPathGroups keyGroups) {
        this.itemExpression = itemExpression;
        this.keyGroups = keyGroups;
    }

    static ItemKeyExtractor from(String itemPath, String... keyPaths) {
        return new ItemKeyExtractor(PathExpression.parse(itemPath), KeyPathGroups.parse(keyPaths));
    }

    List<EnrichmentTarget> targetsFrom(JsonNode root) {
        return itemExpression.select(root).stream()
            .filter(ObjectNode.class::isInstance)
            .map(ObjectNode.class::cast)
            .flatMap(item -> targetsFromItem(item).stream())
            .toList();
    }

    Map<String, JsonNode> entriesByKey(JsonNode root) {
        Map<String, JsonNode> entriesByKey = new HashMap<>();
        itemExpression.select(root).stream()
            .filter(ObjectNode.class::isInstance)
            .map(ObjectNode.class::cast)
            .forEach(entry -> {
                keyGroups.firstKey(entry).ifPresent(key -> entriesByKey.put(key, entry));
            });
        return entriesByKey;
    }

    private List<EnrichmentTarget> targetsFromItem(ObjectNode item) {
        return keyGroups.keysFrom(item)
            .map(key -> new EnrichmentTarget(key, item))
            .toList();
    }
}
