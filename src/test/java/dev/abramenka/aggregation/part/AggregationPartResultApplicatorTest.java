package dev.abramenka.aggregation.part;

import static org.assertj.core.api.Assertions.assertThat;

import dev.abramenka.aggregation.model.AggregationPartResult;
import dev.abramenka.aggregation.model.CompositionSpec;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
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
    void apply_mergesObjectArrayEntriesByIndex() {
        ObjectNode base = object("""
            {
              "items": [
                {"id": "1", "source": true},
                {"id": "2", "source": true}
              ]
            }
            """);
        ObjectNode replacement = object("""
            {
              "items": [
                {"id": "1", "source": true, "account1": [{"id": "acc-1"}]},
                {"id": "2", "source": true}
              ]
            }
            """);
        ObjectNode target = object("""
            {
              "items": [
                {"id": "1", "source": true, "owners1": [{"id": "owner-1"}]},
                {"id": "2", "source": true, "owners1": [{"id": "owner-2"}]}
              ]
            }
            """);

        applicator.apply(AggregationPartResult.patch("part", base, replacement), target);

        assertThat(target.path("items")
                        .path(0)
                        .path("account1")
                        .path(0)
                        .path("id")
                        .asString())
                .isEqualTo("acc-1");
        assertThat(target.path("items")
                        .path(0)
                        .path("owners1")
                        .path(0)
                        .path("id")
                        .asString())
                .isEqualTo("owner-1");
        assertThat(target.path("items")
                        .path(1)
                        .path("owners1")
                        .path(0)
                        .path("id")
                        .asString())
                .isEqualTo("owner-2");
    }

    @Test
    void apply_replacesNonObjectArrayAsSingleChangedValue() {
        ObjectNode base = objectMapper.createObjectNode();
        base.putArray("items").add("a");
        ObjectNode replacement = objectMapper.createObjectNode();
        replacement.putArray("items").add("b");
        ObjectNode target = objectMapper.createObjectNode();
        target.putArray("items").add("current");

        applicator.apply(AggregationPartResult.patch("part", base, replacement), target);

        assertThat(target.path("items").path(0).asString()).isEqualTo("b");
    }

    @Test
    void apply_replacesArrayWhenShapeChanges() {
        ObjectNode base = object("""
            {
              "items": [
                {"id": "1"}
              ]
            }
            """);
        ObjectNode replacement = object("""
            {
              "items": [
                {"id": "1"},
                {"id": "2"}
              ]
            }
            """);
        ObjectNode target = object("""
            {
              "items": [
                {"id": "1", "owners1": [{"id": "owner-1"}]}
              ]
            }
            """);

        applicator.apply(AggregationPartResult.patch("part", base, replacement), target);

        assertThat(target.path("items").size()).isEqualTo(2);
        assertThat(target.path("items").path(0).has("owners1")).isFalse();
    }

    @Test
    void apply_writesMergePatchToNestedTargetPath() {
        ObjectNode target = object("""
            {
              "meta": {}
            }
            """);
        ObjectNode replacement = object("""
            {
              "status": "ok"
            }
            """);

        applicator.apply(
                AggregationPartResult.patch(
                        "part",
                        objectMapper.createObjectNode(),
                        replacement,
                        new CompositionSpec(
                                "/meta/enrichment",
                                CompositionSpec.MergeMode.MERGE_PATCH,
                                CompositionSpec.ConflictPolicy.OVERWRITE)),
                target);

        assertThat(target.path("meta").path("enrichment").path("status").asString()).isEqualTo("ok");
    }

    private ObjectNode object(String raw) {
        try {
            JsonNode node = objectMapper.readTree(raw);
            return (ObjectNode) node;
        } catch (Exception ex) {
            throw new IllegalArgumentException(ex);
        }
    }
}
