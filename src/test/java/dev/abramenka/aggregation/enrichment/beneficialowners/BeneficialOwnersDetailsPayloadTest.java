package dev.abramenka.aggregation.enrichment.beneficialowners;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class BeneficialOwnersDetailsPayloadTest {

    private final JsonMapper objectMapper = JsonMapper.builder().build();
    private final BeneficialOwnersDetailsPayload payload = new BeneficialOwnersDetailsPayload(objectMapper);

    @Test
    void response_buildsIndexedDataArray() {
        ArrayNode details = objectMapper.createArrayNode().add(individual("P-1"));

        ObjectNode response = payload.response(List.of(new ResolvedEntity(3, 1, details)));

        assertThat(response.path("data")).hasSize(1);
        assertThat(response.path("data").path(0).path("dataIndex").asInt()).isEqualTo(3);
        assertThat(response.path("data").path(0).path("ownerIndex").asInt()).isEqualTo(1);
        assertThat(response.path("data")
                        .path(0)
                        .path("details")
                        .path(0)
                        .path("individual")
                        .path("number")
                        .asString())
                .isEqualTo("P-1");
    }

    @Test
    void merge_attachesDeepCopiedDetailsOnlyForMatchingOwners() {
        ObjectNode root = json("""
            {
              "data": [
                {
                  "owners1": [
                    {"entity": {"number": "E-1"}},
                    {"individual": {"number": "I-1"}}
                  ]
                }
              ]
            }
            """);
        ObjectNode enrichmentResponse = json("""
            {
              "data": [
                {
                  "dataIndex": 0,
                  "ownerIndex": 0,
                  "details": [
                    {"individual": {"number": "P-1"}}
                  ]
                },
                {
                  "dataIndex": 0,
                  "ownerIndex": 1,
                  "details": {"ignored": true}
                },
                {
                  "dataIndex": 99,
                  "ownerIndex": 0,
                  "details": [
                    {"individual": {"number": "P-X"}}
                  ]
                }
              ]
            }
            """);

        payload.merge(root, enrichmentResponse);
        ((ObjectNode) enrichmentResponse
                        .path("data")
                        .path(0)
                        .path("details")
                        .path(0)
                        .path("individual"))
                .put("number", "mutated");

        JsonNode firstOwner = root.path("data").path(0).path("owners1").path(0);
        JsonNode secondOwner = root.path("data").path(0).path("owners1").path(1);
        assertThat(firstOwner
                        .path("beneficialOwnersDetails")
                        .path(0)
                        .path("individual")
                        .path("number")
                        .asString())
                .isEqualTo("P-1");
        assertThat(secondOwner.has("beneficialOwnersDetails")).isFalse();
    }

    @Test
    void merge_isNoopWhenResponseDataIsNotAnArray() {
        ObjectNode root = json("""
            {
              "data": [
                {
                  "owners1": [
                    {"entity": {"number": "E-1"}}
                  ]
                }
              ]
            }
            """);

        payload.merge(root, json("""
            {
              "data": {}
            }
            """));

        assertThat(root.path("data").path(0).path("owners1").path(0).has("beneficialOwnersDetails"))
                .isFalse();
    }

    private JsonNode individual(String number) {
        ObjectNode node = objectMapper.createObjectNode();
        node.putObject("individual").put("number", number);
        return node;
    }

    private ObjectNode json(String raw) {
        try {
            return (ObjectNode) objectMapper.readTree(raw);
        } catch (Exception ex) {
            throw new IllegalArgumentException(ex);
        }
    }
}
