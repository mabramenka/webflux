package com.example.aggregation.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.aggregation.enrichment.AccountEnrichment;
import com.example.aggregation.enrichment.OwnersEnrichment;
import com.example.aggregation.client.ForwardedHeaders;
import com.example.aggregation.client.ClientRequestContext;
import com.example.aggregation.client.AccountGroups;
import com.example.aggregation.client.Accounts;
import com.example.aggregation.client.Owners;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@ExtendWith(MockitoExtension.class)
class AggregateServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private AccountGroups accountGroupClient;
    @Mock
    private Accounts accountClient;
    @Mock
    private Owners ownersClient;

    private AggregateService aggregateService;

    @BeforeEach
    void setUp() {
        aggregateService = new AggregateService(
            accountGroupClient,
            List.of(new AccountEnrichment(accountClient), new OwnersEnrichment(ownersClient))
        );
    }

    @Test
    void aggregate_handlesOptionalFailuresAndMergesSuccessfulOptionalResults() {
        ObjectNode inboundRequest = objectMapper.createObjectNode()
            .put("customerId", "cust-1")
            .put("market", "US");
        ClientRequestContext clientRequestContext = new ClientRequestContext(
            ForwardedHeaders.builder().authorization("Bearer token").build(),
            true
        );

        JsonNode accountGroupResponse = json("""
            {
              "customerId": "cust-1",
              "currency": "USD",
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

        when(accountGroupClient.fetchAccountGroup(any(ObjectNode.class), any(ClientRequestContext.class))).thenReturn(Mono.just(accountGroupResponse));
        when(accountClient.fetchAccounts(any(ObjectNode.class), any(ClientRequestContext.class)))
            .thenReturn(Mono.error(new RuntimeException("account down")));

        StepVerifier.create(aggregateService.aggregate(inboundRequest, clientRequestContext))
            .assertNext(aggregated -> {
                ObjectNode root = (ObjectNode) aggregated;
                org.assertj.core.api.Assertions.assertThat(root.path("customerId").asString()).isEqualTo("cust-1");
                org.assertj.core.api.Assertions.assertThat(root.path("data").get(0).path("accounts").get(0).has("account1"))
                    .isFalse();
            })
            .verifyComplete();

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

        StepVerifier.create(aggregateService.aggregate(inboundRequest, clientRequestContext))
            .assertNext(aggregated -> {
                ObjectNode root = (ObjectNode) aggregated;
                JsonNode dataEntry = root.path("data").get(0);
                org.assertj.core.api.Assertions.assertThat(dataEntry.path("account1").get(0).path("amount").decimalValue())
                    .isEqualByComparingTo("10.5");
                org.assertj.core.api.Assertions.assertThat(dataEntry.path("account1").get(1).path("amount").decimalValue())
                    .isEqualByComparingTo("20.0");
                org.assertj.core.api.Assertions.assertThat(dataEntry.path("accounts").get(0).has("account1")).isFalse();
                org.assertj.core.api.Assertions.assertThat(root.has("account1")).isFalse();
            })
            .verifyComplete();
    }

    @Test
    void aggregate_fetchesOnlyEnrichmentSelection() {
        ObjectNode inboundRequest = objectMapper.createObjectNode()
            .put("customerId", "cust-1")
            .put("market", "US");
        inboundRequest.putArray("include").add("account");

        JsonNode accountGroupResponse = json("""
            {
              "customerId": "cust-1",
              "currency": "USD",
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

        when(accountGroupClient.fetchAccountGroup(any(ObjectNode.class), any(ClientRequestContext.class))).thenReturn(Mono.just(accountGroupResponse));
        when(accountClient.fetchAccounts(any(ObjectNode.class), any(ClientRequestContext.class)))
            .thenReturn(Mono.just(accountResponse));

        StepVerifier.create(aggregateService.aggregate(inboundRequest, clientRequestContext()))
            .assertNext(aggregated -> {
                ObjectNode root = (ObjectNode) aggregated;
                JsonNode dataEntry = root.path("data").get(0);
                org.assertj.core.api.Assertions.assertThat(dataEntry.path("account1").get(0).path("amount").decimalValue())
                    .isEqualByComparingTo("10.5");
                org.assertj.core.api.Assertions.assertThat(dataEntry.path("accounts").get(0).has("account1")).isFalse();
            })
            .verifyComplete();

        verify(ownersClient, never()).fetchOwners(any(ObjectNode.class), any(ClientRequestContext.class));
    }

    @Test
    void aggregate_rejectsUnknownEnrichmentSelectionBeforeCallingAccountGroup() {
        ObjectNode inboundRequest = objectMapper.createObjectNode()
            .put("customerId", "cust-1");
        inboundRequest.putArray("include").add("unknown");

        StepVerifier.create(aggregateService.aggregate(inboundRequest, clientRequestContext()))
            .expectErrorSatisfies(error -> org.assertj.core.api.Assertions.assertThat(error)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown"))
            .verify();

        verify(accountGroupClient, never()).fetchAccountGroup(any(ObjectNode.class), any(ClientRequestContext.class));
    }

    @Test
    void aggregate_rejectsNonStringEnrichmentSelectionBeforeCallingAccountGroup() {
        ObjectNode inboundRequest = objectMapper.createObjectNode()
            .put("customerId", "cust-1");
        inboundRequest.putArray("include").add(42);

        StepVerifier.create(aggregateService.aggregate(inboundRequest, clientRequestContext()))
            .expectErrorSatisfies(error -> org.assertj.core.api.Assertions.assertThat(error)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-blank strings"))
            .verify();

        verify(accountGroupClient, never()).fetchAccountGroup(any(ObjectNode.class), any(ClientRequestContext.class));
    }

    @Test
    void aggregate_embedsAccountEntriesIntoMatchingItems() {
        ObjectNode inboundRequest = objectMapper.createObjectNode()
            .put("customerId", "cust-1");
        inboundRequest.putArray("include").add("account");

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

        when(accountGroupClient.fetchAccountGroup(any(ObjectNode.class), any(ClientRequestContext.class))).thenReturn(Mono.just(accountGroupResponse));
        when(accountClient.fetchAccounts(any(ObjectNode.class), any(ClientRequestContext.class)))
            .thenReturn(Mono.just(accountResponse));

        StepVerifier.create(aggregateService.aggregate(inboundRequest, clientRequestContext()))
            .assertNext(aggregated -> {
                ObjectNode root = (ObjectNode) aggregated;
                JsonNode firstData = root.path("data").get(0);
                JsonNode secondData = root.path("data").get(1);
                JsonNode firstAccounts = firstData.path("accounts");
                org.assertj.core.api.Assertions.assertThat(firstAccounts.get(0).has("price")).isFalse();
                org.assertj.core.api.Assertions.assertThat(firstAccounts.get(1).has("price")).isFalse();
                org.assertj.core.api.Assertions.assertThat(firstAccounts.get(0).has("account1")).isFalse();
                org.assertj.core.api.Assertions.assertThat(firstAccounts.get(1).has("account1")).isFalse();
                org.assertj.core.api.Assertions.assertThat(firstData.path("account1").get(0).path("id").asString())
                    .isEqualTo("acc-a");
                org.assertj.core.api.Assertions.assertThat(firstData.path("account1").get(0).path("amount").asString())
                    .isEqualTo("not-a-number");
                org.assertj.core.api.Assertions.assertThat(firstData.path("account1").get(0).path("source").asString())
                    .isEqualTo("account-service");
                org.assertj.core.api.Assertions.assertThat(firstData.path("account1").get(1).path("amount").decimalValue())
                    .isEqualByComparingTo("20.0");
                org.assertj.core.api.Assertions.assertThat(firstData.path("account1").get(1).path("discount").path("code").asString())
                    .isEqualTo("SPRING");
                org.assertj.core.api.Assertions.assertThat(secondData.path("accounts").get(0).has("account1")).isFalse();
                org.assertj.core.api.Assertions.assertThat(secondData.path("account1").get(0).path("amount").decimalValue())
                    .isEqualByComparingTo("30.0");
                org.assertj.core.api.Assertions.assertThat(root.has("account1")).isFalse();
            })
            .verifyComplete();

        ArgumentCaptor<ObjectNode> accountRequestCaptor = ArgumentCaptor.forClass(ObjectNode.class);
        verify(accountClient).fetchAccounts(accountRequestCaptor.capture(), any(ClientRequestContext.class));
        org.assertj.core.api.Assertions.assertThat(accountRequestCaptor.getValue().path("ids").values())
            .extracting(JsonNode::asString)
            .containsExactly("acc-a", "acc-b", "acc-c");
    }

    @Test
    void aggregate_deduplicatesAccountRequestIdsButMergesAllMatchingItems() {
        ObjectNode inboundRequest = objectMapper.createObjectNode()
            .put("customerId", "cust-1");
        inboundRequest.putArray("include").add("account");

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

        when(accountGroupClient.fetchAccountGroup(any(ObjectNode.class), any(ClientRequestContext.class))).thenReturn(Mono.just(accountGroupResponse));
        when(accountClient.fetchAccounts(any(ObjectNode.class), any(ClientRequestContext.class)))
            .thenReturn(Mono.just(accountResponse));

        StepVerifier.create(aggregateService.aggregate(inboundRequest, clientRequestContext()))
            .assertNext(aggregated -> {
                JsonNode data = aggregated.path("data");
                org.assertj.core.api.Assertions.assertThat(data.get(0).path("account1").get(0).path("amount").decimalValue())
                    .isEqualByComparingTo("15.0");
                org.assertj.core.api.Assertions.assertThat(data.get(1).path("account1").get(0).path("amount").decimalValue())
                    .isEqualByComparingTo("15.0");
            })
            .verifyComplete();

        ArgumentCaptor<ObjectNode> accountRequestCaptor = ArgumentCaptor.forClass(ObjectNode.class);
        verify(accountClient).fetchAccounts(accountRequestCaptor.capture(), any(ClientRequestContext.class));
        org.assertj.core.api.Assertions.assertThat(accountRequestCaptor.getValue().path("ids").values())
            .extracting(JsonNode::asString)
            .containsExactly("shared-account");
        verify(ownersClient, never()).fetchOwners(any(ObjectNode.class), any(ClientRequestContext.class));
    }

    @Test
    void aggregate_embedsOwnersIntoDataEntrySiblingArray() {
        ObjectNode inboundRequest = objectMapper.createObjectNode()
            .put("customerId", "cust-1");
        inboundRequest.putArray("include").add("owners");

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

        when(accountGroupClient.fetchAccountGroup(any(ObjectNode.class), any(ClientRequestContext.class))).thenReturn(Mono.just(accountGroupResponse));
        when(ownersClient.fetchOwners(any(ObjectNode.class), any(ClientRequestContext.class)))
            .thenReturn(Mono.just(ownersResponse));

        StepVerifier.create(aggregateService.aggregate(inboundRequest, clientRequestContext()))
            .assertNext(aggregated -> {
                ObjectNode root = (ObjectNode) aggregated;
                JsonNode firstData = root.path("data").get(0);
                JsonNode secondData = root.path("data").get(1);
                JsonNode firstBasicDetails = firstData.path("basicDetails");
                JsonNode secondBasicDetails = secondData.path("basicDetails");
                org.assertj.core.api.Assertions.assertThat(firstBasicDetails.path("owners").get(0).has("owners1"))
                    .isFalse();
                org.assertj.core.api.Assertions.assertThat(firstBasicDetails.has("owners1")).isFalse();
                org.assertj.core.api.Assertions.assertThat(secondBasicDetails.has("owners1")).isFalse();
                org.assertj.core.api.Assertions.assertThat(firstData.path("owners1").get(0).path("name").asString())
                    .isEqualTo("Ada");
                org.assertj.core.api.Assertions.assertThat(firstData.path("owners1").get(1).path("flags").get(0).asString())
                    .isEqualTo("primary");
                org.assertj.core.api.Assertions.assertThat(secondData.path("owners1").get(0).path("name").asString())
                    .isEqualTo("Cid");
            })
            .verifyComplete();

        ArgumentCaptor<ObjectNode> ownersRequestCaptor = ArgumentCaptor.forClass(ObjectNode.class);
        verify(ownersClient).fetchOwners(ownersRequestCaptor.capture(), any(ClientRequestContext.class));
        org.assertj.core.api.Assertions.assertThat(ownersRequestCaptor.getValue().path("ids").values())
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
}
