package com.example.aggregation.enrichment.keyed;

import com.example.aggregation.model.AggregationContext;
import com.example.aggregation.enrichment.AggregationEnrichment;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

public abstract class KeyedArrayEnrichment implements AggregationEnrichment {

    private final EnrichmentRule rule;

    protected KeyedArrayEnrichment(EnrichmentRule rule) {
        this.rule = Objects.requireNonNull(rule, "rule");
    }

    @Override
    public boolean supports(AggregationContext context) {
        return !targetsFrom(context.accountGroupResponse()).isEmpty();
    }

    @Override
    public void merge(ObjectNode root, JsonNode enrichmentResponse) {
        Map<String, JsonNode> entriesByKey = rule.responseRule().entriesByKey(enrichmentResponse);
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
            target.node().withArrayProperty(rule.responseRule().targetField()).add(entry.deepCopy());
        }
    }

    protected record EnrichmentTarget(String key, ObjectNode node) {
    }
}
