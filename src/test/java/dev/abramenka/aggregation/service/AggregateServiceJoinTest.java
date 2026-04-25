package dev.abramenka.aggregation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.abramenka.aggregation.api.AggregateRequest;
import dev.abramenka.aggregation.model.ClientRequestContext;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

class AggregateServiceJoinTest extends AggregateServiceTestSupport {

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

        when(accountGroupClient.fetchAccountGroup(any(ObjectNode.class), anyString(), any(ClientRequestContext.class)))
                .thenReturn(Mono.just(accountGroupResponse));
        when(accountClient.fetchAccounts(any(ObjectNode.class), anyString(), any(ClientRequestContext.class)))
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
        verify(accountClient)
                .fetchAccounts(accountRequestCaptor.capture(), anyString(), any(ClientRequestContext.class));
        assertThat(accountRequestCaptor.getValue().path("ids").values())
                .extracting(JsonNode::asString)
                .containsExactly("acc-a", "acc-b", "acc-c");
    }

    @Test
    void aggregate_tolerantlyAttachesOnlyMatchingKeysWhenDownstreamOmitsSome() {
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

        when(accountGroupClient.fetchAccountGroup(any(ObjectNode.class), anyString(), any(ClientRequestContext.class)))
                .thenReturn(Mono.just(accountGroupResponse));
        when(accountClient.fetchAccounts(any(ObjectNode.class), anyString(), any(ClientRequestContext.class)))
                .thenReturn(Mono.just(accountResponse));

        StepVerifier.create(aggregateService.aggregate(request, clientRequestContext()))
                .assertNext(aggregated -> {
                    JsonNode dataEntry = aggregated.path("data").path(0);
                    assertThat(dataEntry.path("account1").size()).isEqualTo(1);
                    assertThat(dataEntry.path("account1").path(0).path("id").asString())
                            .isEqualTo("acc-a");
                    assertThat(aggregated
                                    .path("meta")
                                    .path("parts")
                                    .path("account")
                                    .path("status")
                                    .asString())
                            .isEqualTo("APPLIED");
                })
                .verifyComplete();

        assertPartMetric("account", "success", 1);
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

        when(accountGroupClient.fetchAccountGroup(any(ObjectNode.class), anyString(), any(ClientRequestContext.class)))
                .thenReturn(Mono.just(accountGroupResponse));
        when(accountClient.fetchAccounts(any(ObjectNode.class), anyString(), any(ClientRequestContext.class)))
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
        verify(accountClient)
                .fetchAccounts(accountRequestCaptor.capture(), anyString(), any(ClientRequestContext.class));
        assertThat(accountRequestCaptor.getValue().path("ids").values())
                .extracting(JsonNode::asString)
                .containsExactly("shared-account");
        verify(ownersClient, never()).fetchOwners(any(ObjectNode.class), anyString(), any(ClientRequestContext.class));
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

        when(accountGroupClient.fetchAccountGroup(any(ObjectNode.class), anyString(), any(ClientRequestContext.class)))
                .thenReturn(Mono.just(accountGroupResponse));
        when(accountClient.fetchAccounts(any(ObjectNode.class), anyString(), any(ClientRequestContext.class)))
                .thenReturn(Mono.just(accountResponse));
        when(ownersClient.fetchOwners(any(ObjectNode.class), anyString(), any(ClientRequestContext.class)))
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

        when(accountGroupClient.fetchAccountGroup(any(ObjectNode.class), anyString(), any(ClientRequestContext.class)))
                .thenReturn(Mono.just(accountGroupResponse));
        when(ownersClient.fetchOwners(any(ObjectNode.class), anyString(), any(ClientRequestContext.class)))
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
        verify(ownersClient).fetchOwners(ownersRequestCaptor.capture(), anyString(), any(ClientRequestContext.class));
        assertThat(ownersRequestCaptor.getValue().path("ids").values())
                .extracting(JsonNode::asString)
                .containsExactly("owner-a", "owner-b", "owner-c");
        verify(accountClient, never())
                .fetchAccounts(any(ObjectNode.class), anyString(), any(ClientRequestContext.class));
    }
}
