package dev.abramenka.aggregation.patch;

import java.util.List;

public record JsonPatchDocument(List<JsonPatchOperation> operations) {

    public JsonPatchDocument {
        operations = List.copyOf(operations);
    }

    public static JsonPatchDocument of(JsonPatchOperation... operations) {
        return new JsonPatchDocument(List.of(operations));
    }

    public boolean isEmpty() {
        return operations.isEmpty();
    }
}
