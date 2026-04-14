package com.example.aggregation.service.part;

import com.example.aggregation.service.AggregationContext;
import com.example.aggregation.service.AggregationPart;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import lombok.Builder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

public abstract class KeyedArrayEnrichmentPart implements AggregationPart {

    private final Rule rule;

    protected KeyedArrayEnrichmentPart(Rule rule) {
        this.rule = Objects.requireNonNull(rule, "rule");
        this.rule.validate();
    }

    @Override
    public boolean supports(AggregationContext context) {
        return !targetsFrom(context.mainResponse()).isEmpty();
    }

    @Override
    public void merge(ObjectNode root, JsonNode partResponse) {
        Map<String, JsonNode> entriesByKey = rule.responseRule().entriesByKey(partResponse);
        targetsFrom(root).forEach(target -> attachMatchingEntry(target, entriesByKey));
    }

    protected ObjectNode requestWithKeys(JsonNode root) {
        ObjectNode request = JsonNodeFactory.instance.objectNode();
        request.set(rule.targetRule().requestKeysField(), keysFrom(targetsFrom(root)));
        return request;
    }

    protected static Rule.RuleBuilder keyedArrayRule() {
        return Rule.builder();
    }

    protected static TargetRule.TargetRuleBuilder mainArrayRule(
        String arrayField,
        Function<ObjectNode, String> keyRule
    ) {
        return TargetRule.builder()
            .targetsFrom(root -> targetsFromArray(root, arrayField, keyRule));
    }

    protected static TargetRule.TargetRuleBuilder mainNestedArrayRule(
        String parentArrayField,
        String nestedArrayField,
        Function<ObjectNode, String> keyRule
    ) {
        return TargetRule.builder()
            .targetsFrom(root -> targetsFromNestedArray(root, parentArrayField, nestedArrayField, keyRule));
    }

    protected static ResponseRule.ResponseRuleBuilder responseArrayRule(
        String entriesField,
        Function<JsonNode, String> keyRule
    ) {
        return ResponseRule.builder()
            .entriesByKey(response -> entriesByKeyFromArray(response, entriesField, keyRule));
    }

    private static List<EnrichmentTarget> targetsFromArray(
        JsonNode root,
        String arrayField,
        Function<ObjectNode, String> keyRule
    ) {
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
    }

    private static List<EnrichmentTarget> targetsFromNestedArray(
        JsonNode root,
        String parentArrayField,
        String nestedArrayField,
        Function<ObjectNode, String> keyRule
    ) {
        JsonNode parentArray = root.path(parentArrayField);
        if (!parentArray.isArray()) {
            return List.of();
        }

        return parentArray.values().stream()
            .filter(ObjectNode.class::isInstance)
            .map(parent -> parent.path(nestedArrayField))
            .filter(JsonNode::isArray)
            .flatMap(nestedArray -> nestedArray.values().stream())
            .filter(ObjectNode.class::isInstance)
            .map(ObjectNode.class::cast)
            .map(node -> new EnrichmentTarget(keyRule.apply(node), node))
            .filter(EnrichmentTarget::hasKey)
            .toList();
    }

    private static Map<String, JsonNode> entriesByKeyFromArray(
        JsonNode response,
        String entriesField,
        Function<JsonNode, String> keyRule
    ) {
        Map<String, JsonNode> entriesByKey = new HashMap<>();
        response.path(entriesField).forEach(entry -> {
            String key = keyRule.apply(entry);
            if (key != null && !key.isBlank()) {
                entriesByKey.put(key, entry);
            }
        });
        return entriesByKey;
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

    private void attachMatchingEntry(EnrichmentTarget target, Map<String, JsonNode> entriesByKey) {
        JsonNode entry = entriesByKey.get(target.key());
        if (entry != null) {
            target.node().set(rule.responseRule().targetField(), entry);
        }
    }

    @Builder
    protected record Rule(
        TargetRule targetRule,
        ResponseRule responseRule
    ) {

        private void validate() {
            Objects.requireNonNull(targetRule, "targetRule");
            Objects.requireNonNull(responseRule, "responseRule");
            targetRule.validate();
            responseRule.validate();
        }
    }

    @Builder
    protected record TargetRule(
        String requestKeysField,
        Function<JsonNode, List<EnrichmentTarget>> targetsFrom
    ) {

        private List<EnrichmentTarget> targetsFrom(JsonNode root) {
            return targetsFrom.apply(root);
        }

        private void validate() {
            Objects.requireNonNull(requestKeysField, "requestKeysField");
            Objects.requireNonNull(targetsFrom, "targetsFrom");
        }
    }

    @Builder
    protected record ResponseRule(
        Function<JsonNode, Map<String, JsonNode>> entriesByKey,
        String targetField
    ) {

        private Map<String, JsonNode> entriesByKey(JsonNode response) {
            return entriesByKey.apply(response);
        }

        private void validate() {
            Objects.requireNonNull(entriesByKey, "entriesByKey");
            Objects.requireNonNull(targetField, "targetField");
        }
    }

    protected record EnrichmentTarget(String key, ObjectNode node) {

        private boolean hasKey() {
            return key != null && !key.isBlank();
        }
    }
}
