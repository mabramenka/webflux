package dev.abramenka.aggregation.enrichment.beneficialowners;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.abramenka.aggregation.client.HttpServiceGroups;
import dev.abramenka.aggregation.client.Owners;
import dev.abramenka.aggregation.error.DownstreamClientException;
import dev.abramenka.aggregation.error.EnrichmentDependencyException;
import dev.abramenka.aggregation.error.ProblemCatalog;
import dev.abramenka.aggregation.model.AggregationContext;
import dev.abramenka.aggregation.model.AggregationPartResult;
import dev.abramenka.aggregation.model.ClientRequestContext;
import dev.abramenka.aggregation.model.ForwardedHeaders;
import dev.abramenka.aggregation.model.PartCriticality;
import dev.abramenka.aggregation.model.PartOutcomeStatus;
import dev.abramenka.aggregation.model.PartSkipReason;
import dev.abramenka.aggregation.model.Projections;
import dev.abramenka.aggregation.patch.JsonPatchApplicator;
import dev.abramenka.aggregation.workflow.WorkflowBindingMetrics;
import dev.abramenka.aggregation.workflow.WorkflowExecutor;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@ExtendWith(MockitoExtension.class)
class BeneficialOwnersEnrichmentTest {

    private static final String OWNERS_CLIENT_NAME = HttpServiceGroups.downstreamClientName(HttpServiceGroups.OWNERS);

    private final JsonMapper objectMapper = JsonMapper.builder().build();
    private final JsonPatchApplicator patchApplicator = new JsonPatchApplicator();

    @Mock
    private Owners ownersClient;

    private BeneficialOwnersEnrichment beneficialOwners;

    @BeforeEach
    void setUp() {
        WorkflowExecutor executor = new WorkflowExecutor(new WorkflowBindingMetrics(new SimpleMeterRegistry()));
        beneficialOwners =
                new BeneficialOwnersEnrichment(ownersClient, new RootEntityTargets(), objectMapper, executor);
    }

    @Test
    void dependencies_includeOwnersEnrichment() {
        assertThat(beneficialOwners.dependencies()).containsExactly("owners");
    }

    @Test
    void criticality_isRequired() {
        assertThat(beneficialOwners.criticality()).isEqualTo(PartCriticality.REQUIRED);
    }

    @Test
    void execute_noRootEntityOwners_skipsWithNoKeysInMain() {
        ObjectNode root = json("""
                {
                  "data": [
                    {
                      "owners1": [
                        {"individual": {"number": "I-1"}}
                      ]
                    }
                  ]
                }
                """);

        StepVerifier.create(beneficialOwners.execute(context(root)))
                .assertNext(result -> {
                    assertThat(result).isInstanceOf(AggregationPartResult.NoOp.class);
                    AggregationPartResult.NoOp noop = (AggregationPartResult.NoOp) result;
                    assertThat(noop.partName()).isEqualTo("beneficialOwners");
                    assertThat(noop.status()).isEqualTo(PartOutcomeStatus.SKIPPED);
                    assertThat(noop.reason()).isEqualTo(PartSkipReason.NO_KEYS_IN_MAIN);
                })
                .verifyComplete();

        verify(ownersClient, times(0)).fetchOwners(any(ObjectNode.class), anyString(), any(ClientRequestContext.class));
    }

    @Test
    void execute_rootEntityOwnerWithNoChildSeeds_writesEmptyDetailsAndDoesNotCallOwners() {
        ObjectNode root = json("""
                {
                  "data": [
                    {
                      "owners1": [
                        {
                          "entity": {
                            "number": "E-1"
                          }
                        }
                      ]
                    }
                  ]
                }
                """);

        StepVerifier.create(executeAndApply(root))
                .assertNext(result -> assertThat(result).isInstanceOf(AggregationPartResult.JsonPatch.class))
                .verifyComplete();

        verify(ownersClient, times(0)).fetchOwners(any(ObjectNode.class), anyString(), any(ClientRequestContext.class));
        JsonNode owner = root.path("data").path(0).path("owners1").path(0);
        assertThat(owner.has("beneficialOwnersDetails")).isTrue();
        assertThat(owner.path("beneficialOwnersDetails").isArray()).isTrue();
        assertThat(owner.path("beneficialOwnersDetails")).isEmpty();
    }

    @Test
    void execute_rootEntityOwnerGetsDetails_individualOwnerDoesNot() {
        ObjectNode root = json("""
                {
                  "data": [
                    {
                      "owners1": [
                        {"individual": {"number": "I-ROOT"}},
                        {
                          "entity": {
                            "number": "E-1",
                            "ownershipStructure": [
                              {
                                "principalOwners": [{"memberDetails": {"number": "P-1"}}],
                                "indirectOwners": [{"memberDetails": {"number": "P-2"}}]
                              }
                            ]
                          }
                        }
                      ]
                    }
                  ]
                }
                """);
        stubResponses(Map.of(Set.of("P-1", "P-2"), respond(individual("P-1"), individual("P-2"))));

        StepVerifier.create(executeAndApply(root)).expectNextCount(1).verifyComplete();

        JsonNode owners = root.path("data").path(0).path("owners1");
        assertThat(owners.path(0).has("beneficialOwnersDetails")).isFalse();
        assertThat(owners.path(1).path("beneficialOwnersDetails")).hasSize(2);
    }

    @Test
    void execute_multipleDataItems_preserveDataIndexAndOwnerIndexTargets() {
        ObjectNode root = json("""
                {
                  "data": [
                    {
                      "owners1": [
                        {
                          "entity": {
                            "number": "E-A",
                            "ownershipStructure": [
                              {"principalOwners": [{"memberDetails": {"number": "X"}}]}
                            ]
                          }
                        }
                      ]
                    },
                    {
                      "owners1": [
                        {
                          "entity": {
                            "number": "E-B",
                            "ownershipStructure": [
                              {"principalOwners": [{"memberDetails": {"number": "Y"}}]}
                            ]
                          }
                        }
                      ]
                    }
                  ]
                }
                """);
        stubResponses(Map.of(Set.of("X"), respond(individual("X")), Set.of("Y"), respond(individual("Y"))));

        StepVerifier.create(executeAndApply(root)).expectNextCount(1).verifyComplete();

        assertThat(root.path("data")
                        .path(0)
                        .path("owners1")
                        .path(0)
                        .path("beneficialOwnersDetails")
                        .path(0)
                        .path("individual")
                        .path("number")
                        .asString())
                .isEqualTo("X");
        assertThat(root.path("data")
                        .path(1)
                        .path("owners1")
                        .path(0)
                        .path("beneficialOwnersDetails")
                        .path(0)
                        .path("individual")
                        .path("number")
                        .asString())
                .isEqualTo("Y");
    }

    @Test
    void execute_multipleRootEntityOwners_getIndependentDetails() {
        ObjectNode root = json("""
                {
                  "data": [
                    {
                      "owners1": [
                        {
                          "entity": {
                            "number": "E-1",
                            "ownershipStructure": [{"principalOwners": [{"memberDetails": {"number": "A"}}]}]
                          }
                        },
                        {
                          "entity": {
                            "number": "E-2",
                            "ownershipStructure": [{"principalOwners": [{"memberDetails": {"number": "B"}}]}]
                          }
                        }
                      ]
                    }
                  ]
                }
                """);
        stubResponses(Map.of(Set.of("A"), respond(individual("A")), Set.of("B"), respond(individual("B"))));

        StepVerifier.create(executeAndApply(root)).expectNextCount(1).verifyComplete();

        assertThat(root.path("data").path(0).path("owners1").path(0).path("beneficialOwnersDetails"))
                .hasSize(1);
        assertThat(root.path("data").path(0).path("owners1").path(1).path("beneficialOwnersDetails"))
                .hasSize(1);
    }

    @Test
    void execute_sameSeedInDifferentGroups_resolvesIndependentlyPerGroup() {
        ObjectNode root = json("""
                {
                  "data": [
                    {
                      "owners1": [
                        {
                          "entity": {
                            "number": "E-1",
                            "ownershipStructure": [{"principalOwners": [{"memberDetails": {"number": "X"}}]}]
                          }
                        },
                        {
                          "entity": {
                            "number": "E-2",
                            "ownershipStructure": [{"principalOwners": [{"memberDetails": {"number": "X"}}]}]
                          }
                        }
                      ]
                    }
                  ]
                }
                """);
        when(ownersClient.fetchOwners(any(ObjectNode.class), anyString(), any(ClientRequestContext.class)))
                .thenReturn(Mono.just(respond(individual("X"))));

        StepVerifier.create(executeAndApply(root)).expectNextCount(1).verifyComplete();

        ArgumentCaptor<ObjectNode> requestCaptor = ArgumentCaptor.forClass(ObjectNode.class);
        verify(ownersClient, times(2))
                .fetchOwners(requestCaptor.capture(), anyString(), any(ClientRequestContext.class));
        List<List<String>> idsByCall = requestCaptor.getAllValues().stream()
                .map(req -> req.path("ids").values().stream()
                        .map(JsonNode::asString)
                        .toList())
                .toList();
        assertThat(idsByCall).containsExactly(List.of("X"), List.of("X"));
    }

    @Test
    void execute_preservesPrincipalBeforeIndirectAndResolvedOrder() {
        ObjectNode root = json("""
                {
                  "data": [
                    {
                      "owners1": [
                        {
                          "entity": {
                            "number": "E-1",
                            "ownershipStructure": [
                              {
                                "principalOwners": [
                                  {"memberDetails": {"number": "P-1"}},
                                  {"memberDetails": {"number": "P-2"}}
                                ],
                                "indirectOwners": [
                                  {"memberDetails": {"number": "I-1"}},
                                  {"memberDetails": {"number": "P-2"}}
                                ]
                              }
                            ]
                          }
                        }
                      ]
                    }
                  ]
                }
                """);
        stubResponses(
                Map.of(Set.of("P-1", "P-2", "I-1"), respond(individual("P-1"), individual("P-2"), individual("I-1"))));

        StepVerifier.create(executeAndApply(root)).expectNextCount(1).verifyComplete();

        JsonNode details = root.path("data").path(0).path("owners1").path(0).path("beneficialOwnersDetails");
        assertThat(details.path(0).path("individual").path("number").asString()).isEqualTo("P-1");
        assertThat(details.path(1).path("individual").path("number").asString()).isEqualTo("P-2");
        assertThat(details.path(2).path("individual").path("number").asString()).isEqualTo("I-1");
    }

    @Test
    void execute_depthSixIsAllowed() {
        ObjectNode root = json("""
                {
                  "data": [
                    {
                      "owners1": [
                        {
                          "entity": {
                            "number": "R",
                            "ownershipStructure": [{"principalOwners": [{"memberDetails": {"number": "L1"}}]}
                            ]
                          }
                        }
                      ]
                    }
                  ]
                }
                """);
        Map<Set<String>, JsonNode> levels = new HashMap<>();
        for (int i = 1; i <= 5; i++) {
            levels.put(Set.of("L" + i), respond(entity("L" + i, List.of("L" + (i + 1)), List.of())));
        }
        levels.put(Set.of("L6"), respond(individual("L6")));
        stubResponses(levels);

        StepVerifier.create(executeAndApply(root)).expectNextCount(1).verifyComplete();

        assertThat(root.path("data")
                        .path(0)
                        .path("owners1")
                        .path(0)
                        .path("beneficialOwnersDetails")
                        .path(0)
                        .path("individual")
                        .path("number")
                        .asString())
                .isEqualTo("L6");
    }

    @Test
    void execute_depthGreaterThanSix_mapsToLegacyEquivalentContractViolation() {
        ObjectNode root = json("""
                {
                  "data": [
                    {
                      "owners1": [
                        {
                          "entity": {
                            "number": "R",
                            "ownershipStructure": [{"principalOwners": [{"memberDetails": {"number": "L1"}}]}
                            ]
                          }
                        }
                      ]
                    }
                  ]
                }
                """);
        Map<Set<String>, JsonNode> levels = new HashMap<>();
        for (int i = 1; i <= 6; i++) {
            levels.put(Set.of("L" + i), respond(entity("L" + i, List.of("L" + (i + 1)), List.of())));
        }
        stubResponses(levels);

        StepVerifier.create(beneficialOwners.execute(context(root)))
                .expectErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(EnrichmentDependencyException.class)
                        .extracting(ex -> ((EnrichmentDependencyException) ex).catalog())
                        .isEqualTo(ProblemCatalog.ENRICH_CONTRACT_VIOLATION))
                .verify();
    }

    @Test
    void execute_cycleSkipsVisitedAndDoesNotLoop() {
        ObjectNode root = json("""
                {
                  "data": [
                    {
                      "owners1": [
                        {
                          "entity": {
                            "number": "R",
                            "ownershipStructure": [{"principalOwners": [{"memberDetails": {"number": "A"}}]}]
                          }
                        }
                      ]
                    }
                  ]
                }
                """);
        stubResponses(Map.of(
                Set.of("A"),
                respond(entity("A", List.of("B"), List.of())),
                Set.of("B"),
                respond(entity("B", List.of("A"), List.of()))));

        StepVerifier.create(executeAndApply(root)).expectNextCount(1).verifyComplete();

        assertThat(root.path("data").path(0).path("owners1").path(0).path("beneficialOwnersDetails"))
                .isEmpty();
    }

    @Test
    void execute_missingRequestedOwner_mapsToLegacyEquivalentContractViolation() {
        ObjectNode root = json("""
                {
                  "data": [
                    {
                      "owners1": [
                        {
                          "entity": {
                            "number": "E-1",
                            "ownershipStructure": [{"principalOwners": [{"memberDetails": {"number": "A"}}]}]
                          }
                        }
                      ]
                    }
                  ]
                }
                """);
        stubResponses(Map.of(Set.of("A"), respond()));

        StepVerifier.create(beneficialOwners.execute(context(root)))
                .expectErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(EnrichmentDependencyException.class)
                        .extracting(ex -> ((EnrichmentDependencyException) ex).catalog())
                        .isEqualTo(ProblemCatalog.ENRICH_CONTRACT_VIOLATION))
                .verify();
    }

    @Test
    void execute_malformedOwnersResponse_mapsToLegacyEquivalentContractViolation() {
        ObjectNode root = json("""
                {
                  "data": [
                    {
                      "owners1": [
                        {
                          "entity": {
                            "number": "E-1",
                            "ownershipStructure": [{"principalOwners": [{"memberDetails": {"number": "A"}}]}]
                          }
                        }
                      ]
                    }
                  ]
                }
                """);
        when(ownersClient.fetchOwners(any(ObjectNode.class), anyString(), any(ClientRequestContext.class)))
                .thenReturn(Mono.just(json("""
                        {"notData": []}
                        """)));

        StepVerifier.create(beneficialOwners.execute(context(root)))
                .expectErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(EnrichmentDependencyException.class)
                        .extracting(ex -> ((EnrichmentDependencyException) ex).catalog())
                        .isEqualTo(ProblemCatalog.ENRICH_CONTRACT_VIOLATION))
                .verify();
    }

    @Test
    void execute_downstreamFacadeExceptionPassThrough_remainsStable() {
        ObjectNode root = json("""
                {
                  "data": [
                    {
                      "owners1": [
                        {
                          "entity": {
                            "number": "E-1",
                            "ownershipStructure": [{"principalOwners": [{"memberDetails": {"number": "A"}}]}]
                          }
                        }
                      ]
                    }
                  ]
                }
                """);
        DownstreamClientException downstreamFailure =
                DownstreamClientException.transport(OWNERS_CLIENT_NAME, new RuntimeException("boom"));
        when(ownersClient.fetchOwners(any(ObjectNode.class), anyString(), any(ClientRequestContext.class)))
                .thenReturn(Mono.error(downstreamFailure));

        StepVerifier.create(beneficialOwners.execute(context(root)))
                .expectErrorSatisfies(error -> assertThat(error).isSameAs(downstreamFailure))
                .verify();
    }

    private Mono<AggregationPartResult> executeAndApply(ObjectNode root) {
        return beneficialOwners.execute(context(root)).map(result -> {
            if (result instanceof AggregationPartResult.JsonPatch jsonPatchResult) {
                patchApplicator.apply(jsonPatchResult.patch(), root);
            }
            return result;
        });
    }

    private void stubResponses(Map<Set<String>, JsonNode> responsesByIds) {
        when(ownersClient.fetchOwners(any(ObjectNode.class), anyString(), any(ClientRequestContext.class)))
                .thenAnswer(invocation -> {
                    ObjectNode request = invocation.getArgument(0);
                    Set<String> ids = new HashSet<>();
                    request.path("ids").values().forEach(node -> ids.add(node.asString()));
                    JsonNode response = responsesByIds.get(ids);
                    if (response == null) {
                        throw new AssertionError(
                                "Unexpected owners ids: " + ids + "; expected one of " + responsesByIds.keySet());
                    }
                    return Mono.just(response);
                });
    }

    private JsonNode respond(JsonNode... dataItems) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode data = response.putArray("data");
        for (JsonNode item : dataItems) {
            data.add(item);
        }
        return response;
    }

    private JsonNode individual(String number) {
        ObjectNode node = objectMapper.createObjectNode();
        node.putObject("individual").put("number", number);
        return node;
    }

    private JsonNode entity(String number, List<String> principalOwners, List<String> indirectOwners) {
        ObjectNode node = objectMapper.createObjectNode();
        ObjectNode entity = node.putObject("entity");
        entity.put("number", number);
        ArrayNode structures = entity.putArray("ownershipStructure");
        ObjectNode structure = structures.addObject();
        ArrayNode principal = structure.putArray("principalOwners");
        for (String ownerNumber : principalOwners) {
            principal.addObject().putObject("memberDetails").put("number", ownerNumber);
        }
        ArrayNode indirect = structure.putArray("indirectOwners");
        for (String ownerNumber : indirectOwners) {
            indirect.addObject().putObject("memberDetails").put("number", ownerNumber);
        }
        return node;
    }

    private ObjectNode json(String raw) {
        try {
            return (ObjectNode) objectMapper.readTree(raw);
        } catch (Exception ex) {
            throw new IllegalArgumentException(ex);
        }
    }

    private AggregationContext context(ObjectNode root) {
        return new AggregationContext(
                root, new ClientRequestContext(ForwardedHeaders.builder().build(), null, Projections.empty()));
    }
}
