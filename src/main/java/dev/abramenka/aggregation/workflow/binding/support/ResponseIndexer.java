package dev.abramenka.aggregation.workflow.binding.support;

import dev.abramenka.aggregation.enrichment.support.keyed.KeyPathGroups;
import dev.abramenka.aggregation.enrichment.support.keyed.PathExpression;
import dev.abramenka.aggregation.workflow.binding.ResponseIndexingRule;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Indexes a downstream response into a map keyed by the first non-blank key drawn from the rule's
 * fallback {@code responseKeyPaths}. The map preserves first-seen order and ignores duplicates so a
 * later entry never overwrites an earlier one (matches existing keyed-enrichment behavior).
 */
public final class ResponseIndexer {

    public Map<String, JsonNode> index(ResponseIndexingRule rule, JsonNode response) {
        PathExpression itemPath = PathExpression.parse(rule.responseItemPath());
        KeyPathGroups keyGroups = KeyPathGroups.parse(rule.responseKeyPaths().toArray(String[]::new));
        Map<String, JsonNode> entries = new LinkedHashMap<>();
        itemPath.select(response).stream()
                .filter(ObjectNode.class::isInstance)
                .map(ObjectNode.class::cast)
                .forEach(entry -> keyGroups.firstKey(entry).ifPresent(key -> entries.putIfAbsent(key, entry)));
        return entries;
    }
}
