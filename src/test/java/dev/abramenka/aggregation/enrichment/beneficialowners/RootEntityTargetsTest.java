package dev.abramenka.aggregation.enrichment.beneficialowners;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class RootEntityTargetsTest {

    private final JsonMapper objectMapper = JsonMapper.builder().build();
    private final RootEntityTargets rootEntityTargets = new RootEntityTargets();

    @Test
    void collect_returnsEmptyWhenDataIsNotAnArray() {
        ObjectNode root = json("""
            {
              "data": {}
            }
            """);

        assertThat(rootEntityTargets.collect(root)).isEmpty();
    }

    @Test
    void collect_skipsNonEntityOwnersAndPreservesIndexes() {
        ObjectNode root = json("""
            {
              "data": [
                {
                  "owners1": {}
                },
                {
                  "owners1": [
                    {"individual": {"number": "I-1"}},
                    {"entity": {"number": "E-1"}},
                    "ignored"
                  ]
                }
              ]
            }
            """);

        assertThat(rootEntityTargets.collect(root)).singleElement().satisfies(target -> {
            assertThat(target.dataIndex()).isEqualTo(1);
            assertThat(target.ownerIndex()).isEqualTo(1);
            assertThat(target.node().path("entity").path("number").asString()).isEqualTo("E-1");
        });
    }

    private ObjectNode json(String raw) {
        try {
            return (ObjectNode) objectMapper.readTree(raw);
        } catch (Exception ex) {
            throw new IllegalArgumentException(ex);
        }
    }
}
