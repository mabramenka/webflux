package dev.abramenka.aggregation.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class AggregationPartResultTest {

    private final JsonMapper objectMapper = JsonMapper.builder().build();

    @Test
    void replacement_defensivelyCopiesInputAndAccessorValue() {
        ObjectNode replacement = objectMapper.createObjectNode();
        replacement.put("value", "original");

        AggregationPartResult.ReplaceDocument result =
                (AggregationPartResult.ReplaceDocument) AggregationPartResult.replacement("part", replacement);
        replacement.put("value", "source-mutated");
        result.replacement().put("value", "accessor-mutated");

        assertThat(result.replacement().path("value").asString()).isEqualTo("original");
    }

    @Test
    void patch_defensivelyCopiesInputAndAccessorValues() {
        ObjectNode base = objectMapper.createObjectNode();
        base.put("value", "base");
        ObjectNode replacement = objectMapper.createObjectNode();
        replacement.put("value", "replacement");

        AggregationPartResult.MergePatch result =
                (AggregationPartResult.MergePatch) AggregationPartResult.patch("part", base, replacement);
        base.put("value", "source-base-mutated");
        replacement.put("value", "source-replacement-mutated");
        result.base().put("value", "accessor-base-mutated");
        result.replacement().put("value", "accessor-replacement-mutated");

        assertThat(result.base().path("value").asString()).isEqualTo("base");
        assertThat(result.replacement().path("value").asString()).isEqualTo("replacement");
    }
}
