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
        List<EnrichmentTarget> targets = targetsFrom(root);
        ObjectNode request = JsonNodeFactory.instance.objectNode();
        request.set(rule.targetRule().requestKeysField(), keysFrom(targets));
        return request;
    }

    protected static Rule.RuleBuilder keyedArrayRule() {
        return Rule.builder();
    }

    protected static TargetRule.TargetRuleBuilder mainNestedArrayToSiblingArrayRule(
        String parentArrayField,
        String nestedArrayField,
        Function<ObjectNode, String> keyRule
    ) {
        return mainNestedArrayToSiblingArrayRule(
            parentArrayField,
            parent -> parent,
            parent -> parent,
            nestedArrayField,
            keyRule
        );
    }

    protected static TargetRule.TargetRuleBuilder mainNestedArrayToSiblingArrayRule(
        String parentArrayField,
        Function<ObjectNode, JsonNode> containerRule,
        String nestedArrayField,
        Function<ObjectNode, String> keyRule
    ) {
        return mainNestedArrayToSiblingArrayRule(
            parentArrayField,
            containerRule,
            containerRule,
            nestedArrayField,
            keyRule
        );
    }

    protected static TargetRule.TargetRuleBuilder mainNestedArrayToSiblingArrayRule(
        String parentArrayField,
        Function<ObjectNode, JsonNode> sourceContainerRule,
        Function<ObjectNode, JsonNode> targetContainerRule,
        String nestedArrayField,
        Function<ObjectNode, String> keyRule
    ) {
        return TargetRule.builder()
            .targetExtractor(root -> siblingArrayTargetsFromNestedArray(
                root,
                parentArrayField,
                sourceContainerRule,
                targetContainerRule,
                nestedArrayField,
                keyRule
            ));
    }

    protected static ResponseRule.ResponseRuleBuilder responseArrayRule(
        String entriesField,
        Function<JsonNode, String> keyRule
    ) {
        return ResponseRule.builder()
            .responseIndexer(response -> entriesByKeyFromArray(response, entriesField, keyRule));
    }

    private static List<EnrichmentTarget> siblingArrayTargetsFromNestedArray(
        JsonNode root,
        String parentArrayField,
        Function<ObjectNode, JsonNode> sourceContainerRule,
        Function<ObjectNode, JsonNode> targetContainerRule,
        String nestedArrayField,
        Function<ObjectNode, String> keyRule
    ) {
        JsonNode parentArray = root.path(parentArrayField);
        if (!parentArray.isArray()) {
            return List.of();
        }

        return parentArray.values().stream()
            .filter(ObjectNode.class::isInstance)
            .map(ObjectNode.class::cast)
            .flatMap(parent -> nestedTargets(
                sourceContainerRule.apply(parent),
                targetContainerRule.apply(parent),
                nestedArrayField,
                keyRule
            ).stream())
            .toList();
    }

    private static List<EnrichmentTarget> nestedTargets(
        JsonNode sourceContainer,
        JsonNode targetContainer,
        String nestedArrayField,
        Function<ObjectNode, String> keyRule
    ) {
        if (!(sourceContainer instanceof ObjectNode sourceContainerObject)
            || !(targetContainer instanceof ObjectNode targetContainerObject)) {
            return List.of();
        }

        JsonNode nestedArray = sourceContainerObject.path(nestedArrayField);
        if (!nestedArray.isArray()) {
            return List.of();
        }

        return nestedArray.values().stream()
            .filter(ObjectNode.class::isInstance)
            .map(ObjectNode.class::cast)
            .map(child -> new EnrichmentTarget(keyRule.apply(child), targetContainerObject))
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
            target.node().withArrayProperty(rule.responseRule().targetField()).add(entry);
        }
    }

    @Builder
    protected record Rule(
        TargetRule targetRule,
        ResponseRule responseRule
    ) {

        protected Rule {
            Objects.requireNonNull(targetRule, "targetRule");
            Objects.requireNonNull(responseRule, "responseRule");
        }
    }

    @Builder
    protected record TargetRule(
        String requestKeysField,
        Function<JsonNode, List<EnrichmentTarget>> targetExtractor
    ) {

        protected TargetRule {
            Objects.requireNonNull(requestKeysField, "requestKeysField");
            Objects.requireNonNull(targetExtractor, "targetExtractor");
        }

        private List<EnrichmentTarget> targetsFrom(JsonNode root) {
            return targetExtractor.apply(root);
        }
    }

    @Builder
    protected record ResponseRule(
        Function<JsonNode, Map<String, JsonNode>> responseIndexer,
        String targetField
    ) {

        protected ResponseRule {
            Objects.requireNonNull(responseIndexer, "responseIndexer");
            Objects.requireNonNull(targetField, "targetField");
        }

        private Map<String, JsonNode> entriesByKey(JsonNode response) {
            return responseIndexer.apply(response);
        }
    }

    protected record EnrichmentTarget(String key, ObjectNode node) {

        private boolean hasKey() {
            return key != null && !key.isBlank();
        }
    }
}
