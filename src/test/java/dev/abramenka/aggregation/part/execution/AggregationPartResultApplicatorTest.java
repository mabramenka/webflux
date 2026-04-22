package dev.abramenka.aggregation.part.execution;

import static org.assertj.core.api.Assertions.assertThat;

import dev.abramenka.aggregation.model.AggregationPartResult;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class AggregationPartResultApplicatorTest {

    private final JsonMapper objectMapper = JsonMapper.builder().build();
    private final AggregationPartResultApplicator applicator = new AggregationPartResultApplicator();

    @Test
    void apply_replacesRootDocument() {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("old", true);
        ObjectNode replacement = objectMapper.createObjectNode();
        replacement.put("new", true);

        applicator.apply(AggregationPartResult.replacement("part", replacement), root);

        assertThat(root.has("old")).isFalse();
        assertThat(root.path("new").asBoolean()).isTrue();
    }

    @Test
    void apply_mergesOnlyChangedObjectProperties() {
        ObjectNode base = objectMapper.createObjectNode();
        base.withObjectProperty("data").put("a", 1);
        base.withObjectProperty("data").put("b", 2);
        ObjectNode replacement = base.deepCopy();
        replacement.withObjectProperty("data").put("a", 10);
        ObjectNode target = base.deepCopy();
        target.withObjectProperty("data").put("c", 3);

        applicator.apply(AggregationPartResult.patch("part", base, replacement), target);

        assertThat(target.path("data").path("a").asInt()).isEqualTo(10);
        assertThat(target.path("data").path("b").asInt()).isEqualTo(2);
        assertThat(target.path("data").path("c").asInt()).isEqualTo(3);
    }

    @Test
    void apply_removesDeletedProperties() {
        ObjectNode base = objectMapper.createObjectNode();
        base.put("removed", true);
        base.put("kept", true);
        ObjectNode replacement = objectMapper.createObjectNode();
        replacement.put("kept", true);
        ObjectNode target = base.deepCopy();

        applicator.apply(AggregationPartResult.patch("part", base, replacement), target);

        assertThat(target.has("removed")).isFalse();
        assertThat(target.path("kept").asBoolean()).isTrue();
    }

    @Test
    void apply_replacesArrayAsSingleChangedValue() {
        ObjectNode base = objectMapper.createObjectNode();
        base.putArray("items").add("a");
        ObjectNode replacement = objectMapper.createObjectNode();
        replacement.putArray("items").add("b");
        ObjectNode target = objectMapper.createObjectNode();
        target.putArray("items").add("current");

        applicator.apply(AggregationPartResult.patch("part", base, replacement), target);

        assertThat(target.path("items").path(0).asString()).isEqualTo("b");
    }
}
