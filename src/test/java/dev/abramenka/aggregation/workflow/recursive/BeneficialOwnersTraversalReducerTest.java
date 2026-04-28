package dev.abramenka.aggregation.workflow.recursive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.abramenka.aggregation.error.EnrichmentDependencyException;
import dev.abramenka.aggregation.error.ProblemCatalog;
import dev.abramenka.aggregation.patch.JsonPatchApplicator;
import dev.abramenka.aggregation.patch.JsonPatchDocument;
import dev.abramenka.aggregation.patch.JsonPatchOperation;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class BeneficialOwnersTraversalReducerTest {

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final JsonPatchApplicator patchApplicator = new JsonPatchApplicator();

    @Test
    void reduce_oneGroup_createsOneJsonPatchWrite() {
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
        JsonNode traversal = json("""
                {
                  "groups": [
                    {
                      "targetMetadata": {"dataIndex": 0, "ownerIndex": 0},
                      "resolvedNodes": [
                        {"number": "I-1", "depth": 1, "node": {"individual": {"number": "I-1"}}}
                      ]
                    }
                  ]
                }
                """);

        JsonPatchDocument patch = new BeneficialOwnersTraversalReducer(root).reduce(traversal);

        assertThat(patch.operations()).singleElement().satisfies(op -> {
            assertThat(op.path()).isEqualTo("/data/0/owners1/0/beneficialOwnersDetails");
            assertThat(op).isInstanceOf(JsonPatchOperation.Add.class);
        });
    }

    @Test
    void reduce_multipleGroups_createsMultipleWritesInGroupOrder() {
        ObjectNode root = json("""
                {
                  "data": [
                    {
                      "owners1": [
                        {"entity": {"number": "E-1"}},
                        {"entity": {"number": "E-2"}}
                      ]
                    }
                  ]
                }
                """);
        JsonNode traversal = json("""
                {
                  "groups": [
                    {
                      "targetMetadata": {"dataIndex": 0, "ownerIndex": 1},
                      "resolvedNodes": [{"node": {"individual": {"number": "I-2"}}}]
                    },
                    {
                      "targetMetadata": {"dataIndex": 0, "ownerIndex": 0},
                      "resolvedNodes": [{"node": {"individual": {"number": "I-1"}}}]
                    }
                  ]
                }
                """);

        JsonPatchDocument patch = new BeneficialOwnersTraversalReducer(root).reduce(traversal);

        assertThat(patch.operations())
                .extracting(JsonPatchOperation::path)
                .containsExactly(
                        "/data/0/owners1/1/beneficialOwnersDetails", "/data/0/owners1/0/beneficialOwnersDetails");
    }

    @Test
    void reduce_preservesResolvedNodeOrderInsideBeneficialOwnersDetails() {
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
        JsonNode traversal = json("""
                {
                  "groups": [
                    {
                      "targetMetadata": {"dataIndex": 0, "ownerIndex": 0},
                      "resolvedNodes": [
                        {"node": {"individual": {"number": "I-1"}}},
                        {"node": {"individual": {"number": "I-2"}}},
                        {"node": {"individual": {"number": "I-3"}}}
                      ]
                    }
                  ]
                }
                """);

        JsonPatchDocument patch = new BeneficialOwnersTraversalReducer(root).reduce(traversal);
        JsonNode value = operationValue(patch.operations().getFirst());

        assertThat(value.path(0).path("individual").path("number").asString()).isEqualTo("I-1");
        assertThat(value.path(1).path("individual").path("number").asString()).isEqualTo("I-2");
        assertThat(value.path(2).path("individual").path("number").asString()).isEqualTo("I-3");
    }

    @Test
    void reduce_deepCopiesResolvedNodePayloads() {
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
        ObjectNode traversal = json("""
                {
                  "groups": [
                    {
                      "targetMetadata": {"dataIndex": 0, "ownerIndex": 0},
                      "resolvedNodes": [
                        {"node": {"individual": {"number": "I-1"}}}
                      ]
                    }
                  ]
                }
                """);

        JsonPatchDocument patch = new BeneficialOwnersTraversalReducer(root).reduce(traversal);
        ((ObjectNode) traversal
                        .path("groups")
                        .path(0)
                        .path("resolvedNodes")
                        .path(0)
                        .path("node")
                        .path("individual"))
                .put("number", "mutated");

        assertThat(operationValue(patch.operations().getFirst())
                        .path(0)
                        .path("individual")
                        .path("number")
                        .asString())
                .isEqualTo("I-1");
    }

    @Test
    void reduce_writesEmptyBeneficialOwnersDetailsArrayWhenResolvedNodesIsEmpty() {
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
        JsonNode traversal = json("""
                {
                  "groups": [
                    {
                      "targetMetadata": {"dataIndex": 0, "ownerIndex": 0},
                      "resolvedNodes": []
                    }
                  ]
                }
                """);

        JsonPatchDocument patch = new BeneficialOwnersTraversalReducer(root).reduce(traversal);
        JsonNode value = operationValue(patch.operations().getFirst());

        assertThat(value.isArray()).isTrue();
        assertThat(value).isEmpty();
    }

    @Test
    void reduce_addsWhenBeneficialOwnersDetailsFieldIsAbsent() {
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
        JsonNode traversal = minimalTraversal();

        JsonPatchDocument patch = new BeneficialOwnersTraversalReducer(root).reduce(traversal);

        assertThat(patch.operations().getFirst()).isInstanceOf(JsonPatchOperation.Add.class);
    }

    @Test
    void reduce_replacesWhenBeneficialOwnersDetailsFieldAlreadyExists() {
        ObjectNode root = json("""
                {
                  "data": [
                    {
                      "owners1": [
                        {
                          "entity": {"number": "E-1"},
                          "beneficialOwnersDetails": [{"individual": {"number": "old"}}]
                        }
                      ]
                    }
                  ]
                }
                """);
        JsonNode traversal = minimalTraversal();

        JsonPatchDocument patch = new BeneficialOwnersTraversalReducer(root).reduce(traversal);

        assertThat(patch.operations().getFirst()).isInstanceOf(JsonPatchOperation.Replace.class);
    }

    @Test
    void reduce_appliedPatchWritesDetailsIntoRootTarget() {
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
        JsonNode traversal = minimalTraversal();

        JsonPatchDocument patch = new BeneficialOwnersTraversalReducer(root).reduce(traversal);
        patchApplicator.apply(patch, root);

        assertThat(root.path("data")
                        .path(0)
                        .path("owners1")
                        .path(0)
                        .path("beneficialOwnersDetails")
                        .path(0)
                        .path("individual")
                        .path("number")
                        .asString())
                .isEqualTo("I-1");
    }

    @Test
    void reduce_failsWhenGroupsFieldIsMissing() {
        assertContractViolation("""
                {
                  "notGroups": []
                }
                """);
    }

    @Test
    void reduce_failsWhenTargetMetadataIsMissing() {
        assertContractViolation("""
                {
                  "groups": [
                    {
                      "resolvedNodes": []
                    }
                  ]
                }
                """);
    }

    @Test
    void reduce_failsWhenDataIndexIsMissing() {
        assertContractViolation("""
                {
                  "groups": [
                    {
                      "targetMetadata": {"ownerIndex": 0},
                      "resolvedNodes": []
                    }
                  ]
                }
                """);
    }

    @Test
    void reduce_failsWhenOwnerIndexIsMissing() {
        assertContractViolation("""
                {
                  "groups": [
                    {
                      "targetMetadata": {"dataIndex": 0},
                      "resolvedNodes": []
                    }
                  ]
                }
                """);
    }

    @Test
    void reduce_failsWhenResolvedNodesIsMissing() {
        assertContractViolation("""
                {
                  "groups": [
                    {
                      "targetMetadata": {"dataIndex": 0, "ownerIndex": 0}
                    }
                  ]
                }
                """);
    }

    @Test
    void reduce_failsWhenResolvedNodePayloadIsMissing() {
        assertContractViolation("""
                {
                  "groups": [
                    {
                      "targetMetadata": {"dataIndex": 0, "ownerIndex": 0},
                      "resolvedNodes": [
                        {"number": "I-1"}
                      ]
                    }
                  ]
                }
                """);
    }

    private void assertContractViolation(String traversalJson) {
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
        JsonNode traversal = json(traversalJson);

        assertThatThrownBy(() -> new BeneficialOwnersTraversalReducer(root).reduce(traversal))
                .isInstanceOf(EnrichmentDependencyException.class)
                .extracting(error -> ((EnrichmentDependencyException) error).catalog())
                .isEqualTo(ProblemCatalog.ENRICH_CONTRACT_VIOLATION);
    }

    private JsonNode operationValue(JsonPatchOperation op) {
        return switch (op) {
            case JsonPatchOperation.Add add -> add.value();
            case JsonPatchOperation.Replace replace -> replace.value();
            case JsonPatchOperation.Test ignored -> throw new AssertionError("Unexpected test operation");
        };
    }

    private JsonNode minimalTraversal() {
        return json("""
                {
                  "groups": [
                    {
                      "targetMetadata": {"dataIndex": 0, "ownerIndex": 0},
                      "resolvedNodes": [
                        {"node": {"individual": {"number": "I-1"}}}
                      ]
                    }
                  ]
                }
                """);
    }

    private ObjectNode json(String raw) {
        try {
            return (ObjectNode) mapper.readTree(raw);
        } catch (Exception ex) {
            throw new IllegalArgumentException(ex);
        }
    }
}
