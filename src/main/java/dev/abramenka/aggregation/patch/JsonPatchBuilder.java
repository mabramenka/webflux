package dev.abramenka.aggregation.patch;

import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * Fluent builder for {@link JsonPatchDocument}. Operations are recorded in declaration order;
 * the builder does not invent intermediate structure beyond what the caller specified.
 */
public final class JsonPatchBuilder {

    private final PatchWriteOptions options;
    private final List<JsonPatchOperation> operations = new ArrayList<>();

    private JsonPatchBuilder(PatchWriteOptions options) {
        this.options = options;
    }

    public static JsonPatchBuilder create() {
        return new JsonPatchBuilder(PatchWriteOptions.DEFAULT);
    }

    public static JsonPatchBuilder create(PatchWriteOptions options) {
        return new JsonPatchBuilder(options);
    }

    public JsonPatchBuilder add(String path, JsonNode value) {
        operations.add(new JsonPatchOperation.Add(path, value));
        return this;
    }

    public JsonPatchBuilder replace(String path, JsonNode value) {
        operations.add(new JsonPatchOperation.Replace(path, value));
        return this;
    }

    public JsonPatchBuilder test(String path, JsonNode value) {
        operations.add(new JsonPatchOperation.Test(path, value));
        return this;
    }

    public PatchWriteOptions options() {
        return options;
    }

    public JsonPatchDocument build() {
        return new JsonPatchDocument(operations);
    }
}
