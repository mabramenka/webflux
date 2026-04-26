package dev.abramenka.aggregation.workflow.binding.support;

import dev.abramenka.aggregation.enrichment.support.keyed.KeyPathGroups;
import dev.abramenka.aggregation.enrichment.support.keyed.PathExpression;
import dev.abramenka.aggregation.workflow.binding.KeyExtractionRule;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Extracts keys (and their owning items) from a JSON document according to a {@link
 * KeyExtractionRule}.
 *
 * <p>Source resolution (ROOT_SNAPSHOT, CURRENT_ROOT, STEP_RESULT) is the caller's responsibility;
 * this class operates on whatever {@code JsonNode} it is given.
 */
public final class KeyExtractor {

    /**
     * Walk the rule's source item path against {@code source} and emit one {@link ExtractedTarget}
     * per item × key, preserving order. Items that yield no key for any path are dropped silently.
     */
    public List<ExtractedTarget> extract(KeyExtractionRule rule, JsonNode source) {
        PathExpression itemPath = PathExpression.parse(rule.sourceItemPath());
        KeyPathGroups keyGroups = KeyPathGroups.parse(rule.keyPaths().toArray(String[]::new));
        return itemPath.select(source).stream()
                .filter(ObjectNode.class::isInstance)
                .map(ObjectNode.class::cast)
                .flatMap(item -> keyGroups.keysFrom(item).map(key -> new ExtractedTarget(key, item)))
                .toList();
    }

    /**
     * Distinct keys for the binding's downstream request, preserving first-seen order. Equivalent
     * to {@code extract(...).stream().map(ExtractedTarget::key).distinct().toList()} but returned
     * as an order-preserving set.
     */
    public Set<String> distinctKeys(KeyExtractionRule rule, JsonNode source) {
        Set<String> keys = new LinkedHashSet<>();
        for (ExtractedTarget target : extract(rule, source)) {
            keys.add(target.key());
        }
        return keys;
    }
}
