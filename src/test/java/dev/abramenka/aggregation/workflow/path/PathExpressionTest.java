package dev.abramenka.aggregation.workflow.path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class PathExpressionTest {

    private final JsonMapper objectMapper = JsonMapper.builder().build();

    @Test
    void select_expandsOnlyArraysForWildcardSegments() {
        JsonNode root = json("""
            {
              "data": {
                "id": "not-an-array"
              }
            }
            """);

        assertThat(PathExpression.parse("$.data[*].id").select(root)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"$.", "$.data.", "$data"})
    void parse_rejectsMalformedAbsolutePaths(String path) {
        assertThatThrownBy(() -> PathExpression.parse(path)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void keyGroups_useFallbackFieldsPerMatchedSourceElement() {
        ObjectNode item = (ObjectNode) json("""
            {
              "owners": [
                {"id": "owner-a", "number": "ignored"},
                {"number": "owner-b"}
              ]
            }
            """);

        List<String> keys = KeyPathGroups.parse("owners[*].id", "owners[*].number")
                .keysFrom(item)
                .toList();

        assertThat(keys).containsExactly("owner-a", "owner-b");
    }

    @Test
    void keyGroups_useFallbackPathsForSingleResponseEntry() {
        ObjectNode entry = (ObjectNode) json("""
            {
              "id": "fallback-id"
            }
            """);

        assertThat(KeyPathGroups.parse("individual.number", "id").firstKey(entry))
                .contains("fallback-id");
    }

    private JsonNode json(String raw) {
        try {
            return objectMapper.readTree(raw);
        } catch (Exception ex) {
            throw new IllegalArgumentException(ex);
        }
    }
}
