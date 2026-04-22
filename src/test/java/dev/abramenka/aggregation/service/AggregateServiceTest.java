package dev.abramenka.aggregation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.abramenka.aggregation.api.AggregateRequest;
import dev.abramenka.aggregation.client.AccountGroups;
import dev.abramenka.aggregation.client.Accounts;
import dev.abramenka.aggregation.client.Owners;
import dev.abramenka.aggregation.enrichment.account.AccountEnrichmentTestFactory;
import dev.abramenka.aggregation.enrichment.owners.OwnersEnrichmentTestFactory;
import dev.abramenka.aggregation.error.DownstreamClientException;
import dev.abramenka.aggregation.error.RequestValidationException;
import dev.abramenka.aggregation.error.UnsupportedAggregationPartException;
import dev.abramenka.aggregation.model.AggregationContext;
import dev.abramenka.aggregation.model.AggregationEnrichment;
import dev.abramenka.aggregation.model.AggregationPart;
import dev.abramenka.aggregation.model.ClientRequestContext;
import dev.abramenka.aggregation.model.ForwardedHeaders;
import dev.abramenka.aggregation.part.AggregationPartExecutor;
import dev.abramenka.aggregation.part.AggregationPartExecutorFactory;
import dev.abramenka.aggregation.part.AggregationPartPlanner;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.NonNull;
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
import tools.jackson.databind.node.ObjectNode;

@ExtendWith(MockitoExtension.class)
class AggregateServiceTest {

    private final JsonMapper objectMapper = JsonMapper.builder().build();

    @Mock
    private AccountGroups accountGroupClient;

    @Mock
    private Accounts accountClient;

    @Mock
    private Owners ownersClient;

    private SimpleMeterRegistry meterRegistry;
    private AggregateService aggregateService;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        aggregateService = new AggregateService(
                accountGroupClient,
                partPlanner(List.of(
                        AccountEnrichmentTestFactory.accountEnrichment(accountClient, objectMapper),
                        OwnersEnrichmentTestFactory.ownersEnrichment(ownersClient, objectMapper))),
                partExecutor(),
                ObservationRegistry.create(),
                objectMapper);
    }

    @Test
    void aggregate_failsWhenSelectedEnrichmentFails_andMergesSuccessfulSelectedResults() {
        AggregateRequest request = new AggregateRequest(List.of("AB123456789"), null);
        ClientRequestContext clientRequestContext = new ClientRequestContext(
                ForwardedHeaders.builder().authorization("Bearer token").build(), true);

        JsonNode accountGroupResponse = json("""
            {
              "customerId": "cust-1",
              "data": [
                {
                  "id": "customer-data-1",
                  "accounts": [
                    {"id": "A"},
                    {"id": "B"}
                  ]
                }
              ]
            }
            """);

        when(accountGroupClient.fetchAccountGroup(any(ObjectNode.class), any(ClientRequestContext.class)))
                .thenReturn(Mono.just(accountGroupResponse));
        when(accountClient.fetchAccounts(any(ObjectNode.class), any(ClientRequestContext.class)))
                .thenReturn(Mono.error(new RuntimeException("account down")));

        StepVerifier.create(aggregateService.aggregate(request, clientRequestContext))
                .expectErrorSatisfies(error ->
                        assertThat(error).isInstanceOf(RuntimeException.class).hasMessage("account down"))
                .verify();
        assertPartMetric("account", "failure", 1);

        JsonNode accountResponse = json("""
            {
              "data": [
                {"id": "A", "amount": 10.50},
                {"id": "B", "amount": 20.00}
              ]
            }
            """);

        when(accountClient.fetchAccounts(any(ObjectNode.class), any(ClientRequestContext.class)))
                .thenReturn(Mono.just(accountResponse));

        StepVerifier.create(aggregateService.aggregate(request, clientRequestContext))
                .assertNext(aggregated -> {
                    ObjectNode root = (ObjectNode) aggregated;
                    JsonNode dataEntry = root.path("data").path(0);
                    assertThat(dataEntry.path("account1").path(0).path("amount").decimalValue())
                            .isEqualByComparingTo("10.5");
                    assertThat(dataEntry.path("account1").path(1).path("amount").decimalValue())
                            .isEqualByComparingTo("20.0");
                    assertThat(dataEntry.path("accounts").path(0).has("account1"))
                            .isFalse();
                    assertThat(root.has("account1")).isFalse();
                })
                .verifyComplete();
        assertPartMetric("account", "success", 1);
    }

    @Test
    void aggregate_fetchesOnlyPartSelection() {
        AggregateRequest request = new AggregateRequest(List.of("AB123456789"), List.of("account"));

        JsonNode accountGroupResponse = json("""
            {
              "customerId": "cust-1",
              "data": [
                {
                  "id": "customer-data-1",
                  "accounts": [
                    {"id": "A"}
                  ]
                }
              ]
            }
            """);
        JsonNode accountResponse = json("""
            {
              "data": [
                {"id": "A", "amount": 10.50}
              ]
            }
            """);

        when(accountGroupClient.fetchAccountGroup(any(ObjectNode.class), any(ClientRequestContext.class)))
                .thenReturn(Mono.just(accountGroupResponse));
        when(accountClient.fetchAccounts(any(ObjectNode.class), any(ClientRequestContext.class)))
                .thenReturn(Mono.just(accountResponse));

        StepVerifier.create(aggregateService.aggregate(request, clientRequestContext()))
                .assertNext(aggregated -> {
                    ObjectNode root = (ObjectNode) aggregated;
                    JsonNode dataEntry = root.path("data").path(0);
                    assertThat(dataEntry.path("account1").path(0).path("amount").decimalValue())
                            .isEqualByComparingTo("10.5");
                    assertThat(dataEntry.path("accounts").path(0).has("account1"))
                            .isFalse();
                })
                .verifyComplete();

        verify(ownersClient, never()).fetchOwners(any(ObjectNode.class), any(ClientRequestContext.class));

        ArgumentCaptor<ObjectNode> accountGroupRequestCaptor = ArgumentCaptor.forClass(ObjectNode.class);
        verify(accountGroupClient)
                .fetchAccountGroup(accountGroupRequestCaptor.capture(), any(ClientRequestContext.class));
        assertThat(accountGroupRequestCaptor.getValue().path("ids").values())
                .extracting(JsonNode::asString)
                .containsExactly("AB123456789");
        assertThat(accountGroupRequestCaptor.getValue().has("include")).isFalse();
    }

    @Test
    void aggregate_rejectsUnknownPartSelectionBeforeCallingAccountGroup() {
        AggregateRequest request = new AggregateRequest(List.of("AB123456789"), List.of("unknown"));

        StepVerifier.create(aggregateService.aggregate(request, clientRequestContext()))
                .expectErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(UnsupportedAggregationPartException.class)
                        .hasMessageContaining("unknown"))
                .verify();

        verify(accountGroupClient, never()).fetchAccountGroup(any(ObjectNode.class), any(ClientRequestContext.class));
    }

    @Test
    void aggregate_rejectsBlankPartSelectionBeforeCallingAccountGroup() {
        AggregateRequest request = new AggregateRequest(List.of("AB123456789"), List.of(" "));

        StepVerifier.create(aggregateService.aggregate(request, clientRequestContext()))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(RequestValidationException.class))
                .verify();

        verify(accountGroupClient, never()).fetchAccountGroup(any(ObjectNode.class), any(ClientRequestContext.class));
    }

    @Test
    void aggregate_rejectsNonObjectAccountGroupResponse() {
        AggregateRequest request = new AggregateRequest(List.of("AB123456789"), null);
        JsonNode accountGroupResponse = json("""
            [
              {"id": "unexpected-array"}
            ]
            """);

        when(accountGroupClient.fetchAccountGroup(any(ObjectNode.class), any(ClientRequestContext.class)))
                .thenReturn(Mono.just(accountGroupResponse));

        StepVerifier.create(aggregateService.aggregate(request, clientRequestContext()))
                .expectErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(DownstreamClientException.class)
                        .hasMessage("Account group client request failed"))
                .verify();

        verify(accountClient, never()).fetchAccounts(any(ObjectNode.class), any(ClientRequestContext.class));
        verify(ownersClient, never()).fetchOwners(any(ObjectNode.class), any(ClientRequestContext.class));
    }

    @Test
    void aggregate_failsWhenSelectedEnrichmentReturnsEmpty() {
        AggregateService service = aggregateServiceWith(emptyEnrichment());
        AggregateRequest request = new AggregateRequest(List.of("AB123456789"), null);
        JsonNode accountGroupResponse = json("""
            {
              "customerId": "cust-1"
            }
            """);

        when(accountGroupClient.fetchAccountGroup(any(ObjectNode.class), any(ClientRequestContext.class)))
                .thenReturn(Mono.just(accountGroupResponse));

        StepVerifier.create(service.aggregate(request, clientRequestContext()))
                .expectErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessage("Required aggregation part 'empty' returned an empty result"))
                .verify();

        assertPartMetric("empty", "empty", 1);
        assertPartMetricMissing("empty", "failure");
    }

    @Test
    void aggregate_expandsBeneficialOwnerDependencies() {
        AggregateService service = aggregateServiceWith(List.of(
                AccountEnrichmentTestFactory.accountEnrichment(accountClient, objectMapper),
                OwnersEnrichmentTestFactory.ownersEnrichment(ownersClient, objectMapper),
                dependentNoopEnrichment("beneficialOwners", "owners")));
        AggregateRequest request = new AggregateRequest(List.of("AB123456789"), List.of("beneficialOwners"));
        JsonNode accountGroupResponse = json("""
            {
              "customerId": "cust-1",
              "data": [
                {
                  "basicDetails": {
                    "owners": [
                      {"id": "owner-a"}
                    ]
                  }
                }
              ]
            }
            """);
        JsonNode ownersResponse = json("""
            {
              "data": [
                {"individual": {"number": "owner-a"}, "name": "Ada"}
              ]
            }
            """);

        when(accountGroupClient.fetchAccountGroup(any(ObjectNode.class), any(ClientRequestContext.class)))
                .thenReturn(Mono.just(accountGroupResponse));
        when(ownersClient.fetchOwners(any(ObjectNode.class), any(ClientRequestContext.class)))
                .thenReturn(Mono.just(ownersResponse));

        StepVerifier.create(service.aggregate(request, clientRequestContext()))
                .assertNext(aggregated -> assertThat(aggregated
                                .path("data")
                                .path(0)
                                .path("owners1")
                                .path(0)
                                .path("name")
                                .asString())
                        .isEqualTo("Ada"))
                .verifyComplete();

        assertPartMetric("beneficialOwners", "success", 1);
        verify(accountClient, never()).fetchAccounts(any(ObjectNode.class), any(ClientRequestContext.class));
    }

    @Test
    void aggregate_failsSelectedEnrichmentWhenFetchFails() {
        AggregateService service = aggregateServiceWith(List.of(failingFetchEnrichment("auditTrail")));
        AggregateRequest request = new AggregateRequest(List.of("AB123456789"), List.of("auditTrail"));
        JsonNode accountGroupResponse = json("""
            {
              "customerId": "cust-1"
            }
            """);

        when(accountGroupClient.fetchAccountGroup(any(ObjectNode.class), any(ClientRequestContext.class)))
                .thenReturn(Mono.just(accountGroupResponse));

        StepVerifier.create(service.aggregate(request, clientRequestContext()))
                .expectErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessage("enrichment down"))
                .verify();

        assertPartMetric("auditTrail", "failure", 1);
    }

    @Test
    void aggregate_skipsUnsupportedEnrichments() {
        AggregateService service = aggregateServiceWith(List.of(unsupportedEnrichment("auditTrail")));
        AggregateRequest request = new AggregateRequest(List.of("AB123456789"), List.of("auditTrail"));
        JsonNode accountGroupResponse = json("""
            {
              "customerId": "cust-1"
            }
            """);

        when(accountGroupClient.fetchAccountGroup(any(ObjectNode.class), any(ClientRequestContext.class)))
                .thenReturn(Mono.just(accountGroupResponse));

        StepVerifier.create(service.aggregate(request, clientRequestContext()))
                .assertNext(aggregated -> {
                    assertThat(aggregated.path("customerId").asString()).isEqualTo("cust-1");
                    assertThat(aggregated.has("unsupportedEnrichmentRan")).isFalse();
                })
                .verifyComplete();
    }

    @Test
    void aggregate_enablesEnrichmentDependencies() {
        AggregateService service = aggregateServiceWith(List.of(
                AccountEnrichmentTestFactory.accountEnrichment(accountClient, objectMapper),
                dependentNoopEnrichment("auditTrail", "account")));
        AggregateRequest request = new AggregateRequest(List.of("AB123456789"), List.of("auditTrail"));
        JsonNode accountGroupResponse = json("""
            {
              "customerId": "cust-1",
              "data": [
                {
                  "accounts": [
                    {"id": "acc-a"}
                  ]
                }
              ]
            }
            """);
        JsonNode accountResponse = json("""
            {
              "data": [
                {"id": "acc-a", "amount": 10.50}
              ]
            }
            """);

        when(accountGroupClient.fetchAccountGroup(any(ObjectNode.class), any(ClientRequestContext.class)))
                .thenReturn(Mono.just(accountGroupResponse));
        when(accountClient.fetchAccounts(any(ObjectNode.class), any(ClientRequestContext.class)))
                .thenReturn(Mono.just(accountResponse));

        StepVerifier.create(service.aggregate(request, clientRequestContext()))
                .assertNext(aggregated -> assertThat(aggregated
                                .path("data")
                                .path(0)
                                .path("account1")
                                .path(0)
                                .path("amount")
                                .decimalValue())
                        .isEqualByComparingTo("10.5"))
                .verifyComplete();

        verify(ownersClient, never()).fetchOwners(any(ObjectNode.class), any(ClientRequestContext.class));
    }

    @Test
    void aggregate_evaluatesDependentSupportAfterDependencyResultApplied() {
        AggregateService service = aggregateServiceWith(List.of(
                rootFlagEnrichment("account", "accountReady"),
                dependentSupportEnrichment("auditTrail", "account", "accountReady", "auditTrailRan")));
        AggregateRequest request = new AggregateRequest(List.of("AB123456789"), List.of("auditTrail"));
        JsonNode accountGroupResponse = json("""
            {
              "customerId": "cust-1"
            }
            """);

        when(accountGroupClient.fetchAccountGroup(any(ObjectNode.class), any(ClientRequestContext.class)))
                .thenReturn(Mono.just(accountGroupResponse));

        StepVerifier.create(service.aggregate(request, clientRequestContext()))
                .assertNext(aggregated -> {
                    assertThat(aggregated.path("accountReady").asBoolean()).isTrue();
                    assertThat(aggregated.path("auditTrailRan").asBoolean()).isTrue();
                })
                .verifyComplete();
    }

    @Test
    void aggregate_appliesSameLevelEnrichmentPatchesWithoutWipingSiblingResults() {
        AggregateService service = aggregateServiceWith(
                List.of(rootFlagEnrichment("account", "enriched"), rootFlagEnrichment("auditTrail", "audited")));
        AggregateRequest request = new AggregateRequest(List.of("AB123456789"), List.of("account", "auditTrail"));
        JsonNode accountGroupResponse = json("""
            {
              "customerId": "cust-1"
            }
            """);

        when(accountGroupClient.fetchAccountGroup(any(ObjectNode.class), any(ClientRequestContext.class)))
                .thenReturn(Mono.just(accountGroupResponse));

        StepVerifier.create(service.aggregate(request, clientRequestContext()))
                .assertNext(aggregated -> {
                    assertThat(aggregated.path("enriched").asBoolean()).isTrue();
                    assertThat(aggregated.path("audited").asBoolean()).isTrue();
                })
                .verifyComplete();
    }

    @Test
    void aggregate_failsSelectedEnrichmentWhenMergeFails() {
        AggregateService service = aggregateServiceWith(failingMergeEnrichment());
        AggregateRequest request = new AggregateRequest(List.of("AB123456789"), null);
        JsonNode accountGroupResponse = json("""
            {
              "customerId": "cust-1"
            }
            """);

        when(accountGroupClient.fetchAccountGroup(any(ObjectNode.class), any(ClientRequestContext.class)))
                .thenReturn(Mono.just(accountGroupResponse));

        StepVerifier.create(service.aggregate(request, clientRequestContext()))
                .expectErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessage("merge failed"))
                .verify();

        assertPartMetric("mergeFailure", "failure", 1);
    }

    @Test
    void aggregate_embedsAccountEntriesIntoMatchingItems() {
        AggregateRequest request = new AggregateRequest(List.of("AB123456789"), List.of("account"));

        JsonNode accountGroupResponse = json("""
            {
              "customerId": "cust-1",
              "data": [
                {
                  "id": "xkjlkjljlj",
                  "accounts": [
                    {"id": "acc-a"},
                    {"id": "acc-b"}
                  ]
                },
                {
                  "id": "another-data-entry",
                  "accounts": [
                    {"id": "acc-c"}
                  ]
                }
              ]
            }
            """);
        JsonNode accountResponse = json("""
            {
              "data": [
                {"id": "acc-a", "amount": "not-a-number", "source": "account-service"},
                {"id": "acc-b", "amount": 20.00, "discount": {"code": "SPRING"}},
                {"id": "acc-c", "amount": 30.00}
              ]
            }
            """);

        when(accountGroupClient.fetchAccountGroup(any(ObjectNode.class), any(ClientRequestContext.class)))
                .thenReturn(Mono.just(accountGroupResponse));
        when(accountClient.fetchAccounts(any(ObjectNode.class), any(ClientRequestContext.class)))
                .thenReturn(Mono.just(accountResponse));

        StepVerifier.create(aggregateService.aggregate(request, clientRequestContext()))
                .assertNext(aggregated -> {
                    ObjectNode root = (ObjectNode) aggregated;
                    JsonNode firstData = root.path("data").path(0);
                    JsonNode secondData = root.path("data").path(1);
                    JsonNode firstAccounts = firstData.path("accounts");
                    assertThat(firstAccounts.path(0).has("price")).isFalse();
                    assertThat(firstAccounts.path(1).has("price")).isFalse();
                    assertThat(firstAccounts.path(0).has("account1")).isFalse();
                    assertThat(firstAccounts.path(1).has("account1")).isFalse();
                    assertThat(firstData.path("account1").path(0).path("id").asString())
                            .isEqualTo("acc-a");
                    assertThat(firstData.path("account1").path(0).path("amount").asString())
                            .isEqualTo("not-a-number");
                    assertThat(firstData.path("account1").path(0).path("source").asString())
                            .isEqualTo("account-service");
                    assertThat(firstData.path("account1").path(1).path("amount").decimalValue())
                            .isEqualByComparingTo("20.0");
                    assertThat(firstData
                                    .path("account1")
                                    .path(1)
                                    .path("discount")
                                    .path("code")
                                    .asString())
                            .isEqualTo("SPRING");
                    assertThat(secondData.path("accounts").path(0).has("account1"))
                            .isFalse();
                    assertThat(secondData
                                    .path("account1")
                                    .path(0)
                                    .path("amount")
                                    .decimalValue())
                            .isEqualByComparingTo("30.0");
                    assertThat(root.has("account1")).isFalse();
                })
                .verifyComplete();

        ArgumentCaptor<ObjectNode> accountRequestCaptor = ArgumentCaptor.forClass(ObjectNode.class);
        verify(accountClient).fetchAccounts(accountRequestCaptor.capture(), any(ClientRequestContext.class));
        assertThat(accountRequestCaptor.getValue().path("ids").values())
                .extracting(JsonNode::asString)
                .containsExactly("acc-a", "acc-b", "acc-c");
    }

    @Test
    void aggregate_failsAccountEnrichmentWhenDownstreamOmitsRequestedKey() {
        AggregateRequest request = new AggregateRequest(List.of("AB123456789"), List.of("account"));

        JsonNode accountGroupResponse = json("""
            {
              "customerId": "cust-1",
              "data": [
                {
                  "accounts": [
                    {"id": "acc-a"},
                    {"id": "acc-b"}
                  ]
                }
              ]
            }
            """);
        JsonNode accountResponse = json("""
            {
              "data": [
                {"id": "acc-a", "amount": 10.50}
              ]
            }
            """);

        when(accountGroupClient.fetchAccountGroup(any(ObjectNode.class), any(ClientRequestContext.class)))
                .thenReturn(Mono.just(accountGroupResponse));
        when(accountClient.fetchAccounts(any(ObjectNode.class), any(ClientRequestContext.class)))
                .thenReturn(Mono.just(accountResponse));

        StepVerifier.create(aggregateService.aggregate(request, clientRequestContext()))
                .expectErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessage(
                                "Required aggregation part 'account' response is missing entries for key(s): acc-b"))
                .verify();

        assertPartMetric("account", "failure", 1);
    }

    @Test
    void aggregate_deduplicatesAccountRequestIdsButMergesAllMatchingItems() {
        AggregateRequest request = new AggregateRequest(List.of("AB123456789"), List.of("account"));

        JsonNode accountGroupResponse = json("""
            {
              "customerId": "cust-1",
              "data": [
                {
                  "id": "first",
                  "accounts": [
                    {"id": "shared-account"}
                  ]
                },
                {
                  "id": "second",
                  "accounts": [
                    {"id": "shared-account"}
                  ]
                }
              ]
            }
            """);
        JsonNode accountResponse = json("""
            {
              "data": [
                {"id": "shared-account", "amount": 15.00}
              ]
            }
            """);

        when(accountGroupClient.fetchAccountGroup(any(ObjectNode.class), any(ClientRequestContext.class)))
                .thenReturn(Mono.just(accountGroupResponse));
        when(accountClient.fetchAccounts(any(ObjectNode.class), any(ClientRequestContext.class)))
                .thenReturn(Mono.just(accountResponse));

        StepVerifier.create(aggregateService.aggregate(request, clientRequestContext()))
                .assertNext(aggregated -> {
                    JsonNode data = aggregated.path("data");
                    assertThat(data.path(0)
                                    .path("account1")
                                    .path(0)
                                    .path("amount")
                                    .decimalValue())
                            .isEqualByComparingTo("15.0");
                    assertThat(data.path(1)
                                    .path("account1")
                                    .path(0)
                                    .path("amount")
                                    .decimalValue())
                            .isEqualByComparingTo("15.0");
                    assertThat(data.path(0).path("account1").path(0))
                            .isNotSameAs(data.path(1).path("account1").path(0));
                })
                .verifyComplete();

        ArgumentCaptor<ObjectNode> accountRequestCaptor = ArgumentCaptor.forClass(ObjectNode.class);
        verify(accountClient).fetchAccounts(accountRequestCaptor.capture(), any(ClientRequestContext.class));
        assertThat(accountRequestCaptor.getValue().path("ids").values())
                .extracting(JsonNode::asString)
                .containsExactly("shared-account");
        verify(ownersClient, never()).fetchOwners(any(ObjectNode.class), any(ClientRequestContext.class));
    }

    @Test
    void aggregate_preservesSameLevelAccountAndOwnersEnrichmentsOnDataItems() {
        AggregateRequest request = new AggregateRequest(List.of("AB123456789"), List.of("account", "owners"));

        JsonNode accountGroupResponse = json("""
            {
              "customerId": "cust-1",
              "data": [
                {
                  "id": "item-1",
                  "accounts": [
                    {"id": "acc-a"}
                  ],
                  "basicDetails": {
                    "owners": [
                      {"id": "owner-a"}
                    ]
                  }
                }
              ]
            }
            """);
        JsonNode accountResponse = json("""
            {
              "data": [
                {"id": "acc-a", "amount": 10.50}
              ]
            }
            """);
        JsonNode ownersResponse = json("""
            {
              "data": [
                {"individual": {"number": "owner-a"}, "name": "Ada"}
              ]
            }
            """);

        when(accountGroupClient.fetchAccountGroup(any(ObjectNode.class), any(ClientRequestContext.class)))
                .thenReturn(Mono.just(accountGroupResponse));
        when(accountClient.fetchAccounts(any(ObjectNode.class), any(ClientRequestContext.class)))
                .thenReturn(Mono.just(accountResponse));
        when(ownersClient.fetchOwners(any(ObjectNode.class), any(ClientRequestContext.class)))
                .thenReturn(Mono.just(ownersResponse));

        StepVerifier.create(aggregateService.aggregate(request, clientRequestContext()))
                .assertNext(aggregated -> {
                    JsonNode item = aggregated.path("data").path(0);
                    assertThat(item.path("account1").path(0).path("amount").decimalValue())
                            .isEqualByComparingTo("10.5");
                    assertThat(item.path("owners1").path(0).path("name").asString())
                            .isEqualTo("Ada");
                })
                .verifyComplete();
    }

    @Test
    void aggregate_embedsOwnersIntoDataEntrySiblingArray() {
        AggregateRequest request = new AggregateRequest(List.of("AB123456789"), List.of("owners"));

        JsonNode accountGroupResponse = json("""
            {
              "customerId": "cust-1",
              "data": [
                {
                  "id": "item-1",
                  "basicDetails": {
                    "owners": [
                      {"id": "owner-a"},
                      {"number": "owner-b"}
                    ]
                  }
                },
                {
                  "id": "item-2",
                  "basicDetails": {
                    "owners": [
                      {"id": "owner-c"}
                    ]
                  }
                }
              ]
            }
            """);
        JsonNode ownersResponse = json("""
            {
              "data": [
                {"individual": {"number": "owner-a"}, "name": "Ada"},
                {"individual": {"number": "owner-b"}, "name": "Bob", "flags": ["primary"]},
                {"id": "owner-c", "name": "Cid"}
              ]
            }
            """);

        when(accountGroupClient.fetchAccountGroup(any(ObjectNode.class), any(ClientRequestContext.class)))
                .thenReturn(Mono.just(accountGroupResponse));
        when(ownersClient.fetchOwners(any(ObjectNode.class), any(ClientRequestContext.class)))
                .thenReturn(Mono.just(ownersResponse));

        StepVerifier.create(aggregateService.aggregate(request, clientRequestContext()))
                .assertNext(aggregated -> {
                    ObjectNode root = (ObjectNode) aggregated;
                    JsonNode firstData = root.path("data").path(0);
                    JsonNode secondData = root.path("data").path(1);
                    JsonNode firstBasicDetails = firstData.path("basicDetails");
                    JsonNode secondBasicDetails = secondData.path("basicDetails");
                    assertThat(firstBasicDetails.path("owners").path(0).has("owners1"))
                            .isFalse();
                    assertThat(firstBasicDetails.has("owners1")).isFalse();
                    assertThat(secondBasicDetails.has("owners1")).isFalse();
                    assertThat(firstData.path("owners1").path(0).path("name").asString())
                            .isEqualTo("Ada");
                    assertThat(firstData
                                    .path("owners1")
                                    .path(1)
                                    .path("flags")
                                    .path(0)
                                    .asString())
                            .isEqualTo("primary");
                    assertThat(secondData.path("owners1").path(0).path("name").asString())
                            .isEqualTo("Cid");
                })
                .verifyComplete();

        ArgumentCaptor<ObjectNode> ownersRequestCaptor = ArgumentCaptor.forClass(ObjectNode.class);
        verify(ownersClient).fetchOwners(ownersRequestCaptor.capture(), any(ClientRequestContext.class));
        assertThat(ownersRequestCaptor.getValue().path("ids").values())
                .extracting(JsonNode::asString)
                .containsExactly("owner-a", "owner-b", "owner-c");
        verify(accountClient, never()).fetchAccounts(any(ObjectNode.class), any(ClientRequestContext.class));
    }

    private JsonNode json(String raw) {
        try {
            return objectMapper.readTree(raw);
        } catch (Exception ex) {
            throw new IllegalArgumentException(ex);
        }
    }

    private ClientRequestContext clientRequestContext() {
        return new ClientRequestContext(ForwardedHeaders.builder().build(), null);
    }

    private AggregateService aggregateServiceWith(AggregationPart part) {
        return aggregateServiceWith(List.of(part));
    }

    private AggregateService aggregateServiceWith(List<AggregationPart> parts) {
        return new AggregateService(
                accountGroupClient, partPlanner(parts), partExecutor(), ObservationRegistry.create(), objectMapper);
    }

    private AggregationPartPlanner partPlanner(List<AggregationPart> parts) {
        return new AggregationPartPlanner(parts);
    }

    private AggregationPartExecutor partExecutor() {
        return AggregationPartExecutorFactory.create(meterRegistry);
    }

    private AggregationEnrichment emptyEnrichment() {
        return new AggregationEnrichment() {
            @Override
            public @NonNull String name() {
                return "empty";
            }

            @Override
            public boolean supports(@NonNull AggregationContext context) {
                return true;
            }

            @Override
            public @NonNull Mono<JsonNode> fetch(@NonNull AggregationContext context) {
                return Mono.empty();
            }

            @Override
            public void merge(@NonNull ObjectNode root, @NonNull JsonNode enrichmentResponse) {
                throw new IllegalStateException("Empty enrichment should not be merged");
            }
        };
    }

    private AggregationEnrichment failingMergeEnrichment() {
        return new AggregationEnrichment() {
            @Override
            public @NonNull String name() {
                return "mergeFailure";
            }

            @Override
            public boolean supports(@NonNull AggregationContext context) {
                return true;
            }

            @Override
            public @NonNull Mono<JsonNode> fetch(@NonNull AggregationContext context) {
                ObjectNode response = objectMapper.createObjectNode();
                response.put("status", "ok");
                return Mono.just(response);
            }

            @Override
            public void merge(@NonNull ObjectNode root, @NonNull JsonNode enrichmentResponse) {
                root.put("failedMergeWasAttempted", true);
                throw new IllegalStateException("merge failed");
            }
        };
    }

    private AggregationEnrichment dependentNoopEnrichment(String name, String dependency) {
        return new AggregationEnrichment() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Set<String> dependencies() {
                return Set.of(dependency);
            }

            @Override
            public Mono<JsonNode> fetch(AggregationContext context) {
                return Mono.just(objectMapper.createObjectNode());
            }

            @Override
            public void merge(ObjectNode root, JsonNode enrichmentResponse) {}
        };
    }

    private AggregationEnrichment rootFlagEnrichment(String name, String flag) {
        return new AggregationEnrichment() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Mono<JsonNode> fetch(AggregationContext context) {
                return Mono.just(objectMapper.createObjectNode());
            }

            @Override
            public void merge(ObjectNode root, JsonNode enrichmentResponse) {
                root.put(flag, true);
            }
        };
    }

    private AggregationEnrichment dependentSupportEnrichment(
            String name, String dependency, String requiredFlag, String targetFlag) {
        return new AggregationEnrichment() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Set<String> dependencies() {
                return Set.of(dependency);
            }

            @Override
            public boolean supports(AggregationContext context) {
                return context.accountGroupResponse().path(requiredFlag).asBoolean(false);
            }

            @Override
            public Mono<JsonNode> fetch(AggregationContext context) {
                return Mono.just(objectMapper.createObjectNode());
            }

            @Override
            public void merge(ObjectNode root, JsonNode enrichmentResponse) {
                root.put(targetFlag, true);
            }
        };
    }

    private AggregationEnrichment failingFetchEnrichment(String name) {
        return new AggregationEnrichment() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Mono<JsonNode> fetch(AggregationContext context) {
                return Mono.error(new IllegalStateException("enrichment down"));
            }

            @Override
            public void merge(ObjectNode root, JsonNode enrichmentResponse) {
                root.put("failedEnrichmentMerged", true);
            }
        };
    }

    private AggregationEnrichment unsupportedEnrichment(String name) {
        return new AggregationEnrichment() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public boolean supports(AggregationContext context) {
                return false;
            }

            @Override
            public Mono<JsonNode> fetch(AggregationContext context) {
                return Mono.just(objectMapper.createObjectNode());
            }

            @Override
            public void merge(ObjectNode root, JsonNode enrichmentResponse) {
                root.put("unsupportedEnrichmentRan", true);
            }
        };
    }

    private void assertPartMetric(String part, String outcome, double count) {
        assertThat(meterRegistry
                        .get("aggregation.part.requests")
                        .tag("part", part)
                        .tag("outcome", outcome)
                        .counter()
                        .count())
                .isEqualTo(count);
    }

    private void assertPartMetricMissing(String part, String outcome) {
        assertThat(meterRegistry
                        .find("aggregation.part.requests")
                        .tag("part", part)
                        .tag("outcome", outcome)
                        .counter())
                .isNull();
    }
}
