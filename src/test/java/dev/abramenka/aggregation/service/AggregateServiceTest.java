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
import dev.abramenka.aggregation.enrichment.AccountEnrichment;
import dev.abramenka.aggregation.enrichment.AggregationEnrichment;
import dev.abramenka.aggregation.enrichment.OwnersEnrichment;
import dev.abramenka.aggregation.error.DownstreamClientException;
import dev.abramenka.aggregation.error.InvalidAggregationRequestException;
import dev.abramenka.aggregation.model.AggregationContext;
import dev.abramenka.aggregation.model.ClientRequestContext;
import dev.abramenka.aggregation.model.ForwardedHeaders;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.codec.DecodingException;
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
                List.of(
                        new AccountEnrichment(accountClient, objectMapper),
                        new OwnersEnrichment(ownersClient, objectMapper)),
                ObservationRegistry.create(),
                new EnrichmentExecutor(meterRegistry),
                new AggregationMerger(),
                objectMapper);
    }

    @Test
    void aggregate_handlesOptionalFailuresAndMergesSuccessfulOptionalResults() {
        AggregateRequest request = new AggregateRequest(List.of("id-x19"), null);
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
                .assertNext(aggregated -> {
                    ObjectNode root = (ObjectNode) aggregated;
                    assertThat(root.path("customerId").asString()).isEqualTo("cust-1");
                    assertThat(root.path("data").get(0).path("accounts").get(0).has("account1"))
                            .isFalse();
                })
                .verifyComplete();
        assertEnrichmentMetric("account", "failure", 1);

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
                    JsonNode dataEntry = root.path("data").get(0);
                    assertThat(dataEntry.path("account1").get(0).path("amount").decimalValue())
                            .isEqualByComparingTo("10.5");
                    assertThat(dataEntry.path("account1").get(1).path("amount").decimalValue())
                            .isEqualByComparingTo("20.0");
                    assertThat(dataEntry.path("accounts").get(0).has("account1"))
                            .isFalse();
                    assertThat(root.has("account1")).isFalse();
                })
                .verifyComplete();
        assertEnrichmentMetric("account", "success", 1);
    }

    @Test
    void aggregate_fetchesOnlyEnrichmentSelection() {
        AggregateRequest request = new AggregateRequest(List.of("id-x19"), List.of("account"));

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
                    JsonNode dataEntry = root.path("data").get(0);
                    assertThat(dataEntry.path("account1").get(0).path("amount").decimalValue())
                            .isEqualByComparingTo("10.5");
                    assertThat(dataEntry.path("accounts").get(0).has("account1"))
                            .isFalse();
                })
                .verifyComplete();

        verify(ownersClient, never()).fetchOwners(any(ObjectNode.class), any(ClientRequestContext.class));

        ArgumentCaptor<ObjectNode> accountGroupRequestCaptor = ArgumentCaptor.forClass(ObjectNode.class);
        verify(accountGroupClient)
                .fetchAccountGroup(accountGroupRequestCaptor.capture(), any(ClientRequestContext.class));
        assertThat(accountGroupRequestCaptor.getValue().path("ids").values())
                .extracting(JsonNode::asString)
                .containsExactly("id-x19");
        assertThat(accountGroupRequestCaptor.getValue().has("include")).isFalse();
    }

    @Test
    void aggregate_rejectsUnknownEnrichmentSelectionBeforeCallingAccountGroup() {
        AggregateRequest request = new AggregateRequest(List.of("id-x19"), List.of("unknown"));

        StepVerifier.create(aggregateService.aggregate(request, clientRequestContext()))
                .expectErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(InvalidAggregationRequestException.class)
                        .hasMessageContaining("unknown"))
                .verify();

        verify(accountGroupClient, never()).fetchAccountGroup(any(ObjectNode.class), any(ClientRequestContext.class));
    }

    @Test
    void aggregate_rejectsBlankEnrichmentSelectionBeforeCallingAccountGroup() {
        AggregateRequest request = new AggregateRequest(List.of("id-x19"), List.of(" "));

        StepVerifier.create(aggregateService.aggregate(request, clientRequestContext()))
                .expectErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(InvalidAggregationRequestException.class)
                        .hasMessageContaining("non-blank strings"))
                .verify();

        verify(accountGroupClient, never()).fetchAccountGroup(any(ObjectNode.class), any(ClientRequestContext.class));
    }

    @Test
    void aggregate_rejectsNonObjectAccountGroupResponse() {
        AggregateRequest request = new AggregateRequest(List.of("id-x19"), null);
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
                        .hasMessageContaining("non-object JSON response"))
                .verify();

        verify(accountClient, never()).fetchAccounts(any(ObjectNode.class), any(ClientRequestContext.class));
        verify(ownersClient, never()).fetchOwners(any(ObjectNode.class), any(ClientRequestContext.class));
    }

    @Test
    void aggregate_mapsUnreadableAccountGroupResponseToDownstreamError() {
        AggregateRequest request = new AggregateRequest(List.of("id-x19"), null);
        DecodingException decodingException = new DecodingException("Invalid JSON");

        when(accountGroupClient.fetchAccountGroup(any(ObjectNode.class), any(ClientRequestContext.class)))
                .thenReturn(Mono.error(decodingException));

        StepVerifier.create(aggregateService.aggregate(request, clientRequestContext()))
                .expectErrorSatisfies(error -> {
                    assertThat(error)
                            .isInstanceOf(DownstreamClientException.class)
                            .hasMessageContaining("unreadable response")
                            .hasCause(decodingException);
                    DownstreamClientException clientException = (DownstreamClientException) error;
                    assertThat(clientException.getStatusCode().value()).isEqualTo(502);
                })
                .verify();

        verify(accountClient, never()).fetchAccounts(any(ObjectNode.class), any(ClientRequestContext.class));
        verify(ownersClient, never()).fetchOwners(any(ObjectNode.class), any(ClientRequestContext.class));
    }

    @Test
    void aggregate_treatsEmptyOptionalEnrichmentResponseAsFailedEnrichment() {
        AggregateService service = aggregateServiceWith(emptyEnrichment());
        AggregateRequest request = new AggregateRequest(List.of("id-x19"), null);
        JsonNode accountGroupResponse = json("""
            {
              "customerId": "cust-1"
            }
            """);

        when(accountGroupClient.fetchAccountGroup(any(ObjectNode.class), any(ClientRequestContext.class)))
                .thenReturn(Mono.just(accountGroupResponse));

        StepVerifier.create(service.aggregate(request, clientRequestContext()))
                .assertNext(aggregated ->
                        assertThat(aggregated.path("customerId").asString()).isEqualTo("cust-1"))
                .verifyComplete();

        assertEnrichmentMetric("empty", "empty", 1);
    }

    @Test
    void aggregate_embedsAccountEntriesIntoMatchingItems() {
        AggregateRequest request = new AggregateRequest(List.of("id-x19"), List.of("account"));

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
                    JsonNode firstData = root.path("data").get(0);
                    JsonNode secondData = root.path("data").get(1);
                    JsonNode firstAccounts = firstData.path("accounts");
                    assertThat(firstAccounts.get(0).has("price")).isFalse();
                    assertThat(firstAccounts.get(1).has("price")).isFalse();
                    assertThat(firstAccounts.get(0).has("account1")).isFalse();
                    assertThat(firstAccounts.get(1).has("account1")).isFalse();
                    assertThat(firstData.path("account1").get(0).path("id").asString())
                            .isEqualTo("acc-a");
                    assertThat(firstData.path("account1").get(0).path("amount").asString())
                            .isEqualTo("not-a-number");
                    assertThat(firstData.path("account1").get(0).path("source").asString())
                            .isEqualTo("account-service");
                    assertThat(firstData.path("account1").get(1).path("amount").decimalValue())
                            .isEqualByComparingTo("20.0");
                    assertThat(firstData
                                    .path("account1")
                                    .get(1)
                                    .path("discount")
                                    .path("code")
                                    .asString())
                            .isEqualTo("SPRING");
                    assertThat(secondData.path("accounts").get(0).has("account1"))
                            .isFalse();
                    assertThat(secondData.path("account1").get(0).path("amount").decimalValue())
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
    void aggregate_deduplicatesAccountRequestIdsButMergesAllMatchingItems() {
        AggregateRequest request = new AggregateRequest(List.of("id-x19"), List.of("account"));

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
                    assertThat(data.get(0)
                                    .path("account1")
                                    .get(0)
                                    .path("amount")
                                    .decimalValue())
                            .isEqualByComparingTo("15.0");
                    assertThat(data.get(1)
                                    .path("account1")
                                    .get(0)
                                    .path("amount")
                                    .decimalValue())
                            .isEqualByComparingTo("15.0");
                    assertThat(data.get(0).path("account1").get(0))
                            .isNotSameAs(data.get(1).path("account1").get(0));
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
    void aggregate_embedsOwnersIntoDataEntrySiblingArray() {
        AggregateRequest request = new AggregateRequest(List.of("id-x19"), List.of("owners"));

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
                    JsonNode firstData = root.path("data").get(0);
                    JsonNode secondData = root.path("data").get(1);
                    JsonNode firstBasicDetails = firstData.path("basicDetails");
                    JsonNode secondBasicDetails = secondData.path("basicDetails");
                    assertThat(firstBasicDetails.path("owners").get(0).has("owners1"))
                            .isFalse();
                    assertThat(firstBasicDetails.has("owners1")).isFalse();
                    assertThat(secondBasicDetails.has("owners1")).isFalse();
                    assertThat(firstData.path("owners1").get(0).path("name").asString())
                            .isEqualTo("Ada");
                    assertThat(firstData
                                    .path("owners1")
                                    .get(1)
                                    .path("flags")
                                    .get(0)
                                    .asString())
                            .isEqualTo("primary");
                    assertThat(secondData.path("owners1").get(0).path("name").asString())
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

    private AggregateService aggregateServiceWith(AggregationEnrichment enrichment) {
        return new AggregateService(
                accountGroupClient,
                List.of(enrichment),
                ObservationRegistry.create(),
                new EnrichmentExecutor(meterRegistry),
                new AggregationMerger(),
                objectMapper);
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

    private void assertEnrichmentMetric(String enrichment, String outcome, double count) {
        assertThat(meterRegistry
                        .get("aggregation.enrichment.requests")
                        .tag("enrichment", enrichment)
                        .tag("outcome", outcome)
                        .counter()
                        .count())
                .isEqualTo(count);
    }
}
