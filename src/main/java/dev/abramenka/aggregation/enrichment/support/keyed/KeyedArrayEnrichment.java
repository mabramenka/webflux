package dev.abramenka.aggregation.enrichment.support.keyed;

import dev.abramenka.aggregation.model.AggregationContext;
import dev.abramenka.aggregation.model.AggregationEnrichment;
import dev.abramenka.aggregation.model.AggregationPartResult;
import dev.abramenka.aggregation.model.PartSkipReason;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import reactor.core.publisher.Mono;
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
    public Mono<AggregationPartResult> execute(ObjectNode rootSnapshot, AggregationContext context) {
        if (targetsFrom(context).isEmpty()) {
            return Mono.just(AggregationPartResult.skipped(name(), PartSkipReason.NO_KEYS_IN_MAIN));
        }
        return AggregationEnrichment.super.execute(rootSnapshot, context);
    }

    @Override
    public void merge(ObjectNode root, JsonNode enrichmentResponse) {
        Map<String, JsonNode> entriesByKey = rule.responseRule().entriesByKey(enrichmentResponse);
        List<EnrichmentTarget> targets = rule.targetRule().targetsFrom(root);
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
}
