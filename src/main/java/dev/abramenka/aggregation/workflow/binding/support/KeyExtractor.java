package dev.abramenka.aggregation.workflow.binding.support;

import dev.abramenka.aggregation.enrichment.support.keyed.KeyPathGroups;
import dev.abramenka.aggregation.enrichment.support.keyed.PathExpression;
import dev.abramenka.aggregation.workflow.binding.KeyExtractionRule;
import dev.abramenka.aggregation.workflow.binding.KeySource;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Extracts keys (and their owning items) from a JSON document according to a {@link
 * KeyExtractionRule}.
 */
public final class KeyExtractor {

    /**
     * Walk the rule's source item path against {@code source} and emit one {@link ExtractedTarget}
     * per item × key, preserving order. Items that yield no key for any path are dropped silently.
     *
     * @throws UnsupportedOperationException for any source other than {@link KeySource#ROOT_SNAPSHOT}
     *     in this phase. Other sources are added in later phases.
     */
    public List<ExtractedTarget> extract(KeyExtractionRule rule, JsonNode source) {
        requireRootSnapshot(rule);
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

    private static void requireRootSnapshot(KeyExtractionRule rule) {
        if (rule.source() != KeySource.ROOT_SNAPSHOT) {
            throw new UnsupportedOperationException(
                    "KeyExtractor in this phase only supports ROOT_SNAPSHOT; got " + rule.source());
        }
    }
}
