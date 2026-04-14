package com.example.aggregation.service.part;

import com.example.aggregation.service.AggregationContext;
import com.example.aggregation.service.AggregationPart;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

public abstract class KeyedArrayEnrichmentPart implements AggregationPart {

    private final EnrichmentRule rule;

    protected KeyedArrayEnrichmentPart(EnrichmentRule rule) {
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

    private List<EnrichmentTarget> targetsFrom(JsonNode root) {
        return rule.targetRule().targetsFrom(root);
    }

    private ArrayNode keysFrom(List<EnrichmentTarget> targets) {
        ArrayNode keys = JsonNodeFactory.instance.arrayNode();
        targets.stream()
            .map(EnrichmentTarget::key)
            .distinct()
            .forEach(keys::add);
        return keys;
    }

    private void attachMatchingEntry(EnrichmentTarget target, Map<String, JsonNode> entriesByKey) {
        JsonNode entry = entriesByKey.get(target.key());
        if (entry != null) {
            target.node().withArrayProperty(rule.responseRule().targetField()).add(entry);
        }
    }

    protected record EnrichmentTarget(String key, ObjectNode node) {
    }
}
