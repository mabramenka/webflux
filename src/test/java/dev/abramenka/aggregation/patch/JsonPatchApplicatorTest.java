package dev.abramenka.aggregation.patch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class JsonPatchApplicatorTest {

    private final JsonMapper objectMapper = JsonMapper.builder().build();
    private final JsonPatchApplicator applicator = new JsonPatchApplicator();

    @Test
    void apply_addsObjectField() {
        ObjectNode root = object("""
            {"data": {"id": "A"}}
            """);

        applicator.apply(JsonPatchDocument.of(new JsonPatchOperation.Add("/data/score", number(42))), root);

        assertThat(root.path("data").path("score").asInt()).isEqualTo(42);
    }

    @Test
    void apply_addReplacesExistingObjectField() {
        ObjectNode root = object("""
            {"data": {"id": "A", "score": 1}}
            """);

        applicator.apply(JsonPatchDocument.of(new JsonPatchOperation.Add("/data/score", number(99))), root);

        assertThat(root.path("data").path("score").asInt()).isEqualTo(99);
    }

    @Test
    void apply_appendsToArrayWithDashToken() {
        ObjectNode root = object("""
            {"data": {"items": [{"id": "A"}]}}
            """);

        applicator.apply(JsonPatchDocument.of(new JsonPatchOperation.Add("/data/items/-", item("B"))), root);

        assertThat(root.path("data").path("items").size()).isEqualTo(2);
        assertThat(root.path("data").path("items").path(1).path("id").asString())
                .isEqualTo("B");
    }

    @Test
    void apply_insertsIntoArrayAtIndex() {
        ObjectNode root = object("""
            {"items": [{"id": "A"}, {"id": "C"}]}
            """);

        applicator.apply(JsonPatchDocument.of(new JsonPatchOperation.Add("/items/1", item("B"))), root);

        assertThat(root.path("items").size()).isEqualTo(3);
        assertThat(root.path("items").path(1).path("id").asString()).isEqualTo("B");
        assertThat(root.path("items").path(2).path("id").asString()).isEqualTo("C");
    }

    @Test
    void apply_replacesObjectField() {
        ObjectNode root = object("""
            {"data": {"score": 1}}
            """);

        applicator.apply(JsonPatchDocument.of(new JsonPatchOperation.Replace("/data/score", number(7))), root);

        assertThat(root.path("data").path("score").asInt()).isEqualTo(7);
    }

    @Test
    void apply_replaceFailsWhenFieldMissing() {
        ObjectNode root = object("""
            {"data": {}}
            """);

        assertThatThrownBy(() -> applicator.apply(
                        JsonPatchDocument.of(new JsonPatchOperation.Replace("/data/score", number(7))), root))
                .isInstanceOf(JsonPatchException.class)
                .hasMessageContaining("replace missing field");
    }

    @Test
    void apply_testPassesOnDeepEquality() {
        ObjectNode root = object("""
            {"data": {"score": 7}}
            """);

        applicator.apply(JsonPatchDocument.of(new JsonPatchOperation.Test("/data/score", number(7))), root);

        assertThat(root.path("data").path("score").asInt()).isEqualTo(7);
    }

    @Test
    void apply_testFailsOnMismatch() {
        ObjectNode root = object("""
            {"data": {"score": 7}}
            """);

        assertThatThrownBy(() -> applicator.apply(
                        JsonPatchDocument.of(new JsonPatchOperation.Test("/data/score", number(8))), root))
                .isInstanceOf(JsonPatchException.class)
                .hasMessageContaining("Test operation failed");
    }

    @Test
    void apply_failsOnMissingParent() {
        ObjectNode root = object("""
            {"data": {}}
            """);

        assertThatThrownBy(() -> applicator.apply(
                        JsonPatchDocument.of(new JsonPatchOperation.Add("/missing/field", number(1))), root))
                .isInstanceOf(JsonPatchException.class)
                .hasMessageContaining("Missing parent");
    }

    @Test
    void apply_appliesOperationsInOrder() {
        ObjectNode root = object("""
            {"data": {"score": 1}}
            """);

        applicator.apply(
                new JsonPatchDocument(java.util.List.of(
                        new JsonPatchOperation.Replace("/data/score", number(2)),
                        new JsonPatchOperation.Test("/data/score", number(2)),
                        new JsonPatchOperation.Add("/data/extra", number(9)))),
                root);

        assertThat(root.path("data").path("score").asInt()).isEqualTo(2);
        assertThat(root.path("data").path("extra").asInt()).isEqualTo(9);
    }

    @Test
    void apply_rejectsRootPointer() {
        ObjectNode root = objectMapper.createObjectNode();

        assertThatThrownBy(
                        () -> applicator.apply(JsonPatchDocument.of(new JsonPatchOperation.Add("", number(1))), root))
                .isInstanceOf(JsonPatchException.class)
                .hasMessageContaining("Root pointer");
    }

    @Test
    void apply_rejectsArrayIndexOutOfBounds() {
        ObjectNode root = object("""
            {"items": [{"id": "A"}]}
            """);

        assertThatThrownBy(() -> applicator.apply(
                        JsonPatchDocument.of(new JsonPatchOperation.Replace("/items/5", item("X"))), root))
                .isInstanceOf(JsonPatchException.class)
                .hasMessageContaining("out of bounds");
    }

    @Test
    void operationDeepCopiesValueOnConstruction() {
        ObjectNode value = objectMapper.createObjectNode();
        value.put("v", 1);
        JsonPatchOperation.Add add = new JsonPatchOperation.Add("/foo", value);

        value.put("v", 2);

        ObjectNode root = objectMapper.createObjectNode();
        applicator.apply(JsonPatchDocument.of(add), root);

        assertThat(root.path("foo").path("v").asInt()).isEqualTo(1);
    }

    private ObjectNode object(String raw) {
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
