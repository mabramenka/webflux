package dev.abramenka.aggregation.enrichment.support.keyed;

import dev.abramenka.aggregation.model.AggregationContext;
import dev.abramenka.aggregation.model.AggregationEnrichment;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

public abstract class KeyedArrayEnrichment implements AggregationEnrichment {

    private final EnrichmentRule rule;
    private final ObjectMapper objectMapper;

    protected KeyedArrayEnrichment(EnrichmentRule rule, ObjectMapper objectMapper) {
        this.rule = Objects.requireNonNull(rule, "rule");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public boolean supports(AggregationContext context) {
        return !targetsFrom(context).isEmpty();
    }

    @Override
    public void merge(ObjectNode root, JsonNode enrichmentResponse) {
        Map<String, JsonNode> entriesByKey = rule.responseRule().entriesByKey(enrichmentResponse);
        List<EnrichmentTarget> targets = rule.targetRule().targetsFrom(root);
        requireAllTargetKeys(targets, entriesByKey);
        targets.forEach(target -> attachMatchingEntry(target, entriesByKey));
    }

    protected ObjectNode requestWithKeys(AggregationContext context) {
        List<EnrichmentTarget> targets = targetsFrom(context);
        ObjectNode request = objectMapper.createObjectNode();
        request.set(rule.targetRule().requestKeysField(), keysFrom(targets));
        return request;
    }

    private List<EnrichmentTarget> targetsFrom(AggregationContext context) {
        return context.memoize(rule, root -> rule.targetRule().targetsFrom(root));
    }

    private ArrayNode keysFrom(List<EnrichmentTarget> targets) {
        ArrayNode keys = objectMapper.createArrayNode();
        targets.stream().map(EnrichmentTarget::key).distinct().forEach(keys::add);
        return keys;
    }

    private void attachMatchingEntry(EnrichmentTarget target, Map<String, JsonNode> entriesByKey) {
        JsonNode entry = entriesByKey.get(target.key());
        if (entry != null) {
            target.node().withArrayProperty(rule.responseRule().targetField()).add(entry.deepCopy());
        }
    }

    private void requireAllTargetKeys(List<EnrichmentTarget> targets, Map<String, JsonNode> entriesByKey) {
        Set<String> missingKeys = new LinkedHashSet<>();
        targets.stream()
                .map(EnrichmentTarget::key)
                .filter(key -> !entriesByKey.containsKey(key))
                .forEach(missingKeys::add);
        if (!missingKeys.isEmpty()) {
            throw new IllegalStateException("Required aggregation part '" + name()
                    + "' response is missing entries for key(s): " + String.join(", ", missingKeys));
        }
    }
}
