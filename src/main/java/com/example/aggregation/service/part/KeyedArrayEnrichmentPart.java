package com.example.aggregation.service.part;

import com.example.aggregation.service.AggregationContext;
import com.example.aggregation.service.AggregationPart;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import lombok.Builder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

public abstract class KeyedArrayEnrichmentPart implements AggregationPart {

    private final Rule rule;

    protected KeyedArrayEnrichmentPart(Rule rule) {
        this.rule = rule;
    }

    @Override
    public boolean supports(AggregationContext context) {
        return !targetsFrom(context.mainResponse()).isEmpty();
    }

    @Override
    public void merge(ObjectNode root, JsonNode partResponse) {
        Map<String, JsonNode> entriesByKey = responseEntriesByKey(partResponse);
        targetsFrom(root).forEach(target -> attachMatchingEntry(target, entriesByKey));
    }

    protected ObjectNode requestWithKeys(JsonNode root) {
        ObjectNode request = JsonNodeFactory.instance.objectNode();
        request.set(rule.requestKeysField(), keysFrom(targetsFrom(root)));
        return request;
    }

    protected static Rule.RuleBuilder keyedArrayRule() {
        return Rule.builder();
    }

    protected static TargetRule targetsFromArray(
        String arrayField,
        Function<ObjectNode, String> keyRule
    ) {
        return root -> {
            JsonNode sourceArray = root.path(arrayField);
            if (!sourceArray.isArray()) {
                return List.of();
            }

            return sourceArray.values().stream()
                .filter(ObjectNode.class::isInstance)
                .map(ObjectNode.class::cast)
                .map(node -> new EnrichmentTarget(keyRule.apply(node), node))
                .filter(EnrichmentTarget::hasKey)
                .toList();
        };
    }

    private List<EnrichmentTarget> targetsFrom(JsonNode root) {
        return rule.targetRule().targetsFrom(root);
    }

    private ArrayNode keysFrom(List<EnrichmentTarget> targets) {
        ArrayNode keys = JsonNodeFactory.instance.arrayNode();
        targets.stream()
            .map(EnrichmentTarget::key)
            .forEach(keys::add);
        return keys;
    }

    private Map<String, JsonNode> responseEntriesByKey(JsonNode response) {
        Map<String, JsonNode> entriesByKey = new HashMap<>();
        response.path(rule.responseEntriesField()).forEach(entry -> {
            String key = entry.path(rule.responseKeyField()).asString("");
            if (!key.isBlank()) {
                entriesByKey.put(key, entry);
            }
        });
        return entriesByKey;
    }

    private void attachMatchingEntry(EnrichmentTarget target, Map<String, JsonNode> entriesByKey) {
        JsonNode entry = entriesByKey.get(target.key());
        if (entry != null) {
            target.node().set(rule.targetEnrichmentField(), entry);
        }
    }

    @Builder
    protected record Rule(
        String requestKeysField,
        TargetRule targetRule,
        String responseEntriesField,
        String responseKeyField,
        String targetEnrichmentField
    ) {
    }

    @FunctionalInterface
    protected interface TargetRule {

        List<EnrichmentTarget> targetsFrom(JsonNode root);
    }

    protected record EnrichmentTarget(String key, ObjectNode node) {

        private boolean hasKey() {
            return key != null && !key.isBlank();
        }
    }
}
