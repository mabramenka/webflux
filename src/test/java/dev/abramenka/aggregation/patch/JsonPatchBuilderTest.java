package dev.abramenka.aggregation.patch;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class JsonPatchBuilderTest {

    private final JsonMapper objectMapper = JsonMapper.builder().build();

    @Test
    void create_defaultsToConservativeOptions() {
        JsonPatchBuilder builder = JsonPatchBuilder.create();

        assertThat(builder.options()).isEqualTo(PatchWriteOptions.DEFAULT);
        assertThat(builder.options().createMissingIntermediates()).isFalse();
    }

    @Test
    void build_emptyBuilderProducesEmptyDocument() {
        JsonPatchDocument document = JsonPatchBuilder.create().build();

        assertThat(document.isEmpty()).isTrue();
        assertThat(document.operations()).isEmpty();
    }

    @Test
    void build_recordsOperationsInDeclarationOrder() {
        JsonNode account = item("acc-1");
        JsonNode score = number(42);

        JsonPatchDocument document = JsonPatchBuilder.create()
                .add("/data/0/account1/-", account)
                .replace("/data/0/riskScore", score)
                .test("/data/0/riskScore", score)
                .build();

        assertThat(document.operations()).hasSize(3);
        assertThat(document.operations().get(0)).isInstanceOf(JsonPatchOperation.Add.class);
        assertThat(document.operations().get(0).path()).isEqualTo("/data/0/account1/-");
        assertThat(document.operations().get(1)).isInstanceOf(JsonPatchOperation.Replace.class);
        assertThat(document.operations().get(1).path()).isEqualTo("/data/0/riskScore");
        assertThat(document.operations().get(2)).isInstanceOf(JsonPatchOperation.Test.class);
    }

    @Test
    void build_appliesViaApplicator() {
        ObjectNode root = objectFrom("""
                {"data": [{"account1": [{"id": "A"}], "riskScore": 1}]}
                """);
        JsonPatchDocument document = JsonPatchBuilder.create()
                .add(
                        JsonPointerBuilder.create()
                                .field("data")
                                .index(0)
                                .field("account1")
                                .append()
                                .build(),
                        item("B"))
                .replace(
                        JsonPointerBuilder.create()
                                .field("data")
                                .index(0)
                                .field("riskScore")
                                .build(),
                        number(99))
                .build();

        new JsonPatchApplicator().apply(document, root);

        assertThat(root.path("data").path(0).path("account1").size()).isEqualTo(2);
        assertThat(root.path("data").path(0).path("account1").path(1).path("id").asString())
                .isEqualTo("B");
        assertThat(root.path("data").path(0).path("riskScore").asInt()).isEqualTo(99);
    }

    @Test
    void create_withOptionsRetainsThem() {
        PatchWriteOptions custom = new PatchWriteOptions(true);

        JsonPatchBuilder builder = JsonPatchBuilder.create(custom);

        assertThat(builder.options()).isEqualTo(custom);
    }

    private ObjectNode objectFrom(String raw) {
        try {
            return (ObjectNode) objectMapper.readTree(raw);
        } catch (Exception ex) {
            throw new IllegalArgumentException(ex);
        }
    }

    private JsonNode number(int value) {
        return objectMapper.getNodeFactory().numberNode(value);
    }

    private JsonNode item(String id) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", id);
        return node;
    }
}
