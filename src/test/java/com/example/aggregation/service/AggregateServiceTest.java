package com.example.aggregation.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.aggregation.client.MainClient;
import com.example.aggregation.client.OwnersClient;
import com.example.aggregation.client.PricingClient;
import com.example.aggregation.client.ProfileClient;
import com.example.aggregation.service.part.OwnersPart;
import com.example.aggregation.service.part.PricingPart;
import com.example.aggregation.service.part.ProfilePart;
import com.example.aggregation.web.DownstreamHeaders;
import com.example.aggregation.web.DownstreamRequest;
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
    private MainClient mainClient;
    @Mock
    private ProfileClient profileClient;
    @Mock
    private PricingClient pricingClient;
    @Mock
    private OwnersClient ownersClient;

    private AggregateService aggregateService;

    @BeforeEach
    void setUp() {
        aggregateService = new AggregateService(
            mainClient,
            List.of(new ProfilePart(profileClient), new PricingPart(pricingClient), new OwnersPart(ownersClient))
        );
    }

    @Test
    void aggregate_handlesOptionalFailuresAndMergesSuccessfulOptionalResults() {
        ObjectNode inboundRequest = objectMapper.createObjectNode()
            .put("customerId", "cust-1")
            .put("market", "US");
        DownstreamRequest downstreamRequest = new DownstreamRequest(
            DownstreamHeaders.builder().authorization("Bearer token").build(),
            true
        );

        JsonNode mainResponse = json("""
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

        when(mainClient.postMain(any(ObjectNode.class), any(DownstreamRequest.class))).thenReturn(Mono.just(mainResponse));
        when(profileClient.postProfile(any(ObjectNode.class), any(DownstreamRequest.class)))
            .thenReturn(Mono.error(new RuntimeException("profile down")));
        when(pricingClient.postPricing(any(ObjectNode.class), any(DownstreamRequest.class)))
            .thenReturn(Mono.error(new RuntimeException("pricing down")));

        StepVerifier.create(aggregateService.aggregate(inboundRequest, downstreamRequest))
            .assertNext(aggregated -> {
                ObjectNode root = (ObjectNode) aggregated;
                org.assertj.core.api.Assertions.assertThat(root.path("customerId").asString()).isEqualTo("cust-1");
                org.assertj.core.api.Assertions.assertThat(root.has("customerProfile")).isFalse();
                org.assertj.core.api.Assertions.assertThat(root.path("data").get(0).path("accounts").get(0).has("account1"))
                    .isFalse();
            })
            .verifyComplete();

        JsonNode profileResponse = json("{\"tier\":\"GOLD\"}");
        JsonNode pricingResponse = json("""
            {
              "data": [
                {"id": "A", "amount": 10.50},
                {"id": "B", "amount": 20.00}
              ]
            }
            """);

        when(profileClient.postProfile(any(ObjectNode.class), any(DownstreamRequest.class)))
            .thenReturn(Mono.just(profileResponse));
        when(pricingClient.postPricing(any(ObjectNode.class), any(DownstreamRequest.class)))
            .thenReturn(Mono.just(pricingResponse));

        StepVerifier.create(aggregateService.aggregate(inboundRequest, downstreamRequest))
            .assertNext(aggregated -> {
                ObjectNode root = (ObjectNode) aggregated;
                org.assertj.core.api.Assertions.assertThat(root.path("customerProfile").path("tier").asString())
                    .isEqualTo("GOLD");
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
    void aggregate_fetchesOnlyRequestedParts() {
        ObjectNode inboundRequest = objectMapper.createObjectNode()
            .put("customerId", "cust-1")
            .put("market", "US");
        inboundRequest.putArray("include").add("profile");

        JsonNode mainResponse = json("""
            {
              "customerId": "cust-1",
              "currency": "USD",
              "items": [
                {"itemId": "A"}
              ]
            }
            """);
        JsonNode profileResponse = json("{\"tier\":\"GOLD\"}");

        when(mainClient.postMain(any(ObjectNode.class), any(DownstreamRequest.class))).thenReturn(Mono.just(mainResponse));
        when(profileClient.postProfile(any(ObjectNode.class), any(DownstreamRequest.class)))
            .thenReturn(Mono.just(profileResponse));

        StepVerifier.create(aggregateService.aggregate(inboundRequest, downstreamRequest()))
            .assertNext(aggregated -> {
                ObjectNode root = (ObjectNode) aggregated;
                org.assertj.core.api.Assertions.assertThat(root.path("customerProfile").path("tier").asString())
                    .isEqualTo("GOLD");
                org.assertj.core.api.Assertions.assertThat(root.path("items").get(0).has("price")).isFalse();
            })
            .verifyComplete();

        verify(pricingClient, never()).postPricing(any(ObjectNode.class), any(DownstreamRequest.class));
    }

    @Test
    void aggregate_rejectsUnknownRequestedPartsBeforeCallingMain() {
        ObjectNode inboundRequest = objectMapper.createObjectNode()
            .put("customerId", "cust-1");
        inboundRequest.putArray("include").add("unknown");

        StepVerifier.create(aggregateService.aggregate(inboundRequest, downstreamRequest()))
            .expectErrorSatisfies(error -> org.assertj.core.api.Assertions.assertThat(error)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown"))
            .verify();

        verify(mainClient, never()).postMain(any(ObjectNode.class), any(DownstreamRequest.class));
    }

    @Test
    void aggregate_rejectsNonStringRequestedPartsBeforeCallingMain() {
        ObjectNode inboundRequest = objectMapper.createObjectNode()
            .put("customerId", "cust-1");
        inboundRequest.putArray("include").add(42);

        StepVerifier.create(aggregateService.aggregate(inboundRequest, downstreamRequest()))
            .expectErrorSatisfies(error -> org.assertj.core.api.Assertions.assertThat(error)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-blank strings"))
            .verify();

        verify(mainClient, never()).postMain(any(ObjectNode.class), any(DownstreamRequest.class));
    }

    @Test
    void aggregate_embedsPricingEntriesIntoMatchingItems() {
        ObjectNode inboundRequest = objectMapper.createObjectNode()
            .put("customerId", "cust-1");
        inboundRequest.putArray("include").add("pricing");

        JsonNode mainResponse = json("""
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
        JsonNode pricingResponse = json("""
            {
              "data": [
                {"id": "acc-a", "amount": "not-a-number", "source": "pricing-service"},
                {"id": "acc-b", "amount": 20.00, "discount": {"code": "SPRING"}},
                {"id": "acc-c", "amount": 30.00}
              ]
            }
            """);

        when(mainClient.postMain(any(ObjectNode.class), any(DownstreamRequest.class))).thenReturn(Mono.just(mainResponse));
        when(pricingClient.postPricing(any(ObjectNode.class), any(DownstreamRequest.class)))
            .thenReturn(Mono.just(pricingResponse));

        StepVerifier.create(aggregateService.aggregate(inboundRequest, downstreamRequest()))
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
                    .isEqualTo("pricing-service");
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

        ArgumentCaptor<ObjectNode> pricingRequestCaptor = ArgumentCaptor.forClass(ObjectNode.class);
        verify(pricingClient).postPricing(pricingRequestCaptor.capture(), any(DownstreamRequest.class));
        org.assertj.core.api.Assertions.assertThat(pricingRequestCaptor.getValue().path("ids").values())
            .extracting(JsonNode::asString)
            .containsExactly("acc-a", "acc-b", "acc-c");
        verify(profileClient, never()).postProfile(any(ObjectNode.class), any(DownstreamRequest.class));
    }

    @Test
    void aggregate_embedsOwnersIntoBasicDetailsSiblingArray() {
        ObjectNode inboundRequest = objectMapper.createObjectNode()
            .put("customerId", "cust-1");
        inboundRequest.putArray("include").add("owners");

        JsonNode mainResponse = json("""
            {
              "customerId": "cust-1",
              "data": [
                {
                  "id": "item-1",
                  "basicDetails": {
                    "owners": [
                      {"id": "owner-a"},
                      {"id": "owner-b"}
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
                {"id": "owner-a", "name": "Ada"},
                {"id": "owner-b", "name": "Bob", "flags": ["primary"]},
                {"id": "owner-c", "name": "Cid"}
              ]
            }
            """);

        when(mainClient.postMain(any(ObjectNode.class), any(DownstreamRequest.class))).thenReturn(Mono.just(mainResponse));
        when(ownersClient.postOwners(any(ObjectNode.class), any(DownstreamRequest.class)))
            .thenReturn(Mono.just(ownersResponse));

        StepVerifier.create(aggregateService.aggregate(inboundRequest, downstreamRequest()))
            .assertNext(aggregated -> {
                ObjectNode root = (ObjectNode) aggregated;
                JsonNode firstBasicDetails = root.path("data").get(0).path("basicDetails");
                JsonNode secondBasicDetails = root.path("data").get(1).path("basicDetails");
                org.assertj.core.api.Assertions.assertThat(firstBasicDetails.path("owners").get(0).has("owners1"))
                    .isFalse();
                org.assertj.core.api.Assertions.assertThat(firstBasicDetails.path("owners1").get(0).path("name").asString())
                    .isEqualTo("Ada");
                org.assertj.core.api.Assertions.assertThat(firstBasicDetails.path("owners1").get(1).path("flags").get(0).asString())
                    .isEqualTo("primary");
                org.assertj.core.api.Assertions.assertThat(secondBasicDetails.path("owners1").get(0).path("name").asString())
                    .isEqualTo("Cid");
            })
            .verifyComplete();

        ArgumentCaptor<ObjectNode> ownersRequestCaptor = ArgumentCaptor.forClass(ObjectNode.class);
        verify(ownersClient).postOwners(ownersRequestCaptor.capture(), any(DownstreamRequest.class));
        org.assertj.core.api.Assertions.assertThat(ownersRequestCaptor.getValue().path("ids").values())
            .extracting(JsonNode::asString)
            .containsExactly("owner-a", "owner-b", "owner-c");
        verify(profileClient, never()).postProfile(any(ObjectNode.class), any(DownstreamRequest.class));
        verify(pricingClient, never()).postPricing(any(ObjectNode.class), any(DownstreamRequest.class));
    }

    private JsonNode json(String raw) {
        try {
            return objectMapper.readTree(raw);
        } catch (Exception ex) {
            throw new IllegalArgumentException(ex);
        }
    }

    private DownstreamRequest downstreamRequest() {
        return new DownstreamRequest(DownstreamHeaders.builder().build(), null);
    }
}
