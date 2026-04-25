package dev.abramenka.aggregation.workflow.binding.support;

import static org.assertj.core.api.Assertions.assertThat;

import dev.abramenka.aggregation.workflow.binding.ResponseIndexingRule;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ResponseIndexerTest {

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final ResponseIndexer indexer = new ResponseIndexer();

    @Test
    void indexesByFirstKeyPath() {
        JsonNode response = parse("""
                {"data": [
                  {"id": "a", "value": 1},
                  {"id": "b", "value": 2}
                ]}
                """);
        ResponseIndexingRule rule = new ResponseIndexingRule("$.data[*]", List.of("id"));

        Map<String, JsonNode> index = indexer.index(rule, response);

        assertThat(index).containsOnlyKeys("a", "b");
        assertThat(index)
                .extractingByKey("a")
                .extracting(node -> node.path("value").asInt())
                .isEqualTo(1);
    }

    @Test
    void fallsBackToSecondaryKeyPath() {
        JsonNode response = parse("""
                {"data": [
                  {"individual": {"number": "n1"}},
                  {"id": "i2"}
                ]}
                """);
        ResponseIndexingRule rule = new ResponseIndexingRule("$.data[*]", List.of("individual.number", "id"));

        Map<String, JsonNode> index = indexer.index(rule, response);

        assertThat(index).containsOnlyKeys("n1", "i2");
    }

    @Test
    void firstSeenWinsOnDuplicateKeys() {
        JsonNode response = parse("""
                {"data": [
                  {"id": "a", "value": 1},
                  {"id": "a", "value": 2}
                ]}
                """);
        ResponseIndexingRule rule = new ResponseIndexingRule("$.data[*]", List.of("id"));

        Map<String, JsonNode> index = indexer.index(rule, response);

        assertThat(index)
                .extractingByKey("a")
                .extracting(node -> node.path("value").asInt())
                .isEqualTo(1);
    }

    private JsonNode parse(String raw) {
        try {
            return mapper.readTree(raw);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
