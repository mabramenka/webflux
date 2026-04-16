package com.example.aggregation.enrichment.keyed;

import com.example.aggregation.enrichment.keyed.KeyedArrayEnrichment.EnrichmentTarget;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

public final class EnrichmentRule {

    private final TargetRule targetRule;
    private final ResponseRule responseRule;

    private EnrichmentRule(TargetRule targetRule, ResponseRule responseRule) {
        this.targetRule = Objects.requireNonNull(targetRule, "targetRule");
        this.responseRule = Objects.requireNonNull(responseRule, "responseRule");
    }

    TargetRule targetRule() {
        return targetRule;
    }

    ResponseRule responseRule() {
        return responseRule;
    }

    public static EnrichmentRuleBuilder builder() {
        return new EnrichmentRuleBuilder();
    }

    public static final class EnrichmentRuleBuilder {

        @Nullable
        private Function<JsonNode, List<EnrichmentTarget>> targetExtractor;
        @Nullable
        private Function<JsonNode, Map<String, JsonNode>> responseIndexer;
        @Nullable
        private String requestKeysField;
        @Nullable
        private String targetField;

        public EnrichmentRuleBuilder mainItems(String itemPath, String... keyPaths) {
            ItemKeyExtractor extractor = ItemKeyExtractor.from(itemPath, keyPaths);
            this.targetExtractor = extractor::targetsFrom;
            return this;
        }

        public EnrichmentRuleBuilder responseItems(String itemPath, String... keyPaths) {
            ItemKeyExtractor extractor = ItemKeyExtractor.from(itemPath, keyPaths);
            this.responseIndexer = extractor::entriesByKey;
            return this;
        }

        public EnrichmentRuleBuilder requestKeysField(String requestKeysField) {
            this.requestKeysField = requestKeysField;
            return this;
        }

        public EnrichmentRuleBuilder targetField(String targetField) {
            this.targetField = targetField;
            return this;
        }

        public EnrichmentRule build() {
            Objects.requireNonNull(targetExtractor, "targetExtractor");
            Objects.requireNonNull(responseIndexer, "responseIndexer");
            Objects.requireNonNull(requestKeysField, "requestKeysField");
            Objects.requireNonNull(targetField, "targetField");
            return new EnrichmentRule(
                new TargetRule(requestKeysField, targetExtractor),
                new ResponseRule(responseIndexer, targetField)
            );
        }
    }

    record TargetRule(
        String requestKeysField,
        Function<JsonNode, List<EnrichmentTarget>> targetExtractor
    ) {

        TargetRule {
            Objects.requireNonNull(requestKeysField, "requestKeysField");
            Objects.requireNonNull(targetExtractor, "targetExtractor");
        }

        List<EnrichmentTarget> targetsFrom(JsonNode root) {
            return targetExtractor.apply(root);
        }
    }

    record ResponseRule(
        Function<JsonNode, Map<String, JsonNode>> responseIndexer,
        String targetField
    ) {

        ResponseRule {
            Objects.requireNonNull(responseIndexer, "responseIndexer");
            Objects.requireNonNull(targetField, "targetField");
        }

        Map<String, JsonNode> entriesByKey(JsonNode response) {
            return responseIndexer.apply(response);
        }
    }
}
