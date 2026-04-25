package dev.abramenka.aggregation.patch;

import tools.jackson.databind.JsonNode;

public sealed interface JsonPatchOperation
        permits JsonPatchOperation.Add, JsonPatchOperation.Replace, JsonPatchOperation.Test {

    String path();

    record Add(String path, JsonNode value) implements JsonPatchOperation {
        public Add {
            value = value.deepCopy();
        }
    }

    record Replace(String path, JsonNode value) implements JsonPatchOperation {
        public Replace {
            value = value.deepCopy();
        }
    }

    record Test(String path, JsonNode value) implements JsonPatchOperation {
        public Test {
            value = value.deepCopy();
        }
    }
}
