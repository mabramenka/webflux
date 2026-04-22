package dev.abramenka.aggregation.enrichment.beneficialowners;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.abramenka.aggregation.client.Owners;
import dev.abramenka.aggregation.model.AggregationContext;
import dev.abramenka.aggregation.model.AggregationPartSelection;
import dev.abramenka.aggregation.model.ClientRequestContext;
import dev.abramenka.aggregation.model.ForwardedHeaders;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@ExtendWith(MockitoExtension.class)
class BeneficialOwnersEnrichmentTest {

    private final JsonMapper objectMapper = JsonMapper.builder().build();

    @Mock
    private Owners ownersClient;

    private SimpleMeterRegistry meterRegistry;
    private BeneficialOwnersEnrichment beneficialOwners;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        beneficialOwners = new BeneficialOwnersEnrichment(
                new OwnershipResolver(ownersClient, objectMapper), meterRegistry, objectMapper);
    }

    @Test
    void enrichment_attachesBeneficialOwnersDetailsToRootEntityOnly() {
        ObjectNode root = json("""
            {
              "data": [
                {
                  "id": "item-1",
                  "owners1": [
                    {"individual": {"number": "I-1"}, "name": "Ada"},
                    {
                      "entity": {
                        "number": "E-1",
                        "ownershipStructure": [
                          {
                            "principalOwners": [
                              {"memberDetails": {"number": "P-1"}}
                            ],
                            "indirectOwners": [
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

        when(ownersClient.fetchOwners(any(ObjectNode.class), any(ClientRequestContext.class)))
                .thenAnswer(invocation -> {
                    ObjectNode response = objectMapper.createObjectNode();
                    response.putArray("data").add(individual("P-1")).add(individual("P-2"));
                    return Mono.just(response);
                });

        StepVerifier.create(fetchAndMerge(root)).verifyComplete();

        JsonNode owners = root.path("data").path(0).path("owners1");
        assertThat(owners.path(0).has("beneficialOwnersDetails")).isFalse();
        JsonNode resolvedArray = owners.path(1).path("beneficialOwnersDetails");
        assertThat(resolvedArray.isArray()).isTrue();
        assertThat(resolvedArray.size()).isEqualTo(2);
        assertThat(resolvedArray.path(0).path("individual").path("number").asString())
                .isEqualTo("P-1");
        assertThat(resolvedArray.path(1).path("individual").path("number").asString())
                .isEqualTo("P-2");

        assertTreeMetric("success", 1);
    }

    @Test
    void enrichment_failsWhenAnyRootEntityResolutionFails() {
        ObjectNode root = json("""
            {
              "data": [
                {
                  "owners1": [
                    {
                      "entity": {
                        "number": "E-OK",
                        "ownershipStructure": [
                          {"principalOwners": [{"memberDetails": {"number": "OK-1"}}]}
                        ]
                      }
                    },
                    {
                      "entity": {
                        "number": "E-FAIL",
                        "ownershipStructure": [
                          {"principalOwners": [{"memberDetails": {"number": "FAIL-1"}}]}
                        ]
                      }
                    }
                  ]
                }
              ]
            }
            """);

        when(ownersClient.fetchOwners(any(ObjectNode.class), any(ClientRequestContext.class)))
                .thenAnswer(invocation -> {
                    ObjectNode request = invocation.getArgument(0);
                    Set<String> ids = new HashSet<>();
                    request.path("ids").values().forEach(node -> ids.add(node.asString()));
                    if (ids.contains("OK-1")) {
                        ObjectNode response = objectMapper.createObjectNode();
                        response.putArray("data").add(individual("OK-1"));
                        return Mono.just(response);
                    }
                    return Mono.error(new RuntimeException("owners 5xx"));
                });

        StepVerifier.create(fetchAndMerge(root))
                .expectErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(BeneficialOwnersResolutionException.class)
                        .hasMessage("owners client failed while resolving beneficial owners"))
                .verify();

        JsonNode owners = root.path("data").path(0).path("owners1");
        assertThat(owners.path(0).has("beneficialOwnersDetails")).isFalse();
        assertThat(owners.path(1).has("beneficialOwnersDetails")).isFalse();

        assertTreeMetric("success", 1);
        assertTreeMetric("failure", 1);
    }

    @Test
    void enrichment_isNoopWhenOwners1ContainsOnlyIndividuals() {
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

        StepVerifier.create(fetchAndMerge(root)).verifyComplete();

        assertThat(root.path("data").path(0).path("owners1").path(0).has("beneficialOwnersDetails"))
                .isFalse();
        verify(ownersClient, never()).fetchOwners(any(ObjectNode.class), any(ClientRequestContext.class));
    }

    @Test
    void enrichment_processesRootEntitiesAcrossMultipleDataItems() {
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

        when(ownersClient.fetchOwners(any(ObjectNode.class), any(ClientRequestContext.class)))
                .thenAnswer(invocation -> {
                    ObjectNode request = invocation.getArgument(0);
                    String onlyId = request.path("ids").path(0).asString();
                    ObjectNode response = objectMapper.createObjectNode();
                    response.putArray("data").add(individual(onlyId));
                    return Mono.just(response);
                });

        StepVerifier.create(fetchAndMerge(root)).verifyComplete();

        JsonNode firstOwner = root.path("data").path(0).path("owners1").path(0);
        JsonNode secondOwner = root.path("data").path(1).path("owners1").path(0);
        assertThat(firstOwner
                        .path("beneficialOwnersDetails")
                        .path(0)
                        .path("individual")
                        .path("number")
                        .asString())
                .isEqualTo("X");
        assertThat(secondOwner
                        .path("beneficialOwnersDetails")
                        .path(0)
                        .path("individual")
                        .path("number")
                        .asString())
                .isEqualTo("Y");
        assertTreeMetric("success", 2);
    }

    @Test
    void dependencies_includeOwnersEnrichment() {
        assertThat(beneficialOwners.dependencies()).containsExactly("owners");
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

    private Mono<Void> fetchAndMerge(ObjectNode root) {
        return beneficialOwners
                .fetch(context(root))
                .doOnNext(response -> beneficialOwners.merge(root, response))
                .then();
    }

    private AggregationContext context(ObjectNode root) {
        return new AggregationContext(
                root,
                new ClientRequestContext(ForwardedHeaders.builder().build(), null),
                AggregationPartSelection.from(null));
    }

    private void assertTreeMetric(String outcome, double count) {
        assertThat(meterRegistry
                        .get("aggregation.beneficial_owners.tree")
                        .tag("outcome", outcome)
                        .counter()
                        .count())
                .isEqualTo(count);
    }
}
