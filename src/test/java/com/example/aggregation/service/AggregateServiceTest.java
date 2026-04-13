package com.example.aggregation.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.aggregation.client.MainClient;
import com.example.aggregation.client.PricingClient;
import com.example.aggregation.client.ProfileClient;
import com.example.aggregation.service.part.PricingPart;
import com.example.aggregation.service.part.ProfilePart;
import com.example.aggregation.web.DownstreamHeaders;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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

    private AggregateService aggregateService;

    @BeforeEach
    void setUp() {
        aggregateService = new AggregateService(
            mainClient,
            List.of(new ProfilePart(profileClient), new PricingPart(pricingClient))
        );
    }

    @Test
    void aggregate_handlesOptionalFailuresAndMergesSuccessfulOptionalResults() {
        ObjectNode inboundRequest = objectMapper.createObjectNode()
            .put("customerId", "cust-1")
            .put("market", "US");
        DownstreamHeaders headers = DownstreamHeaders.builder().authorization("Bearer token").build();

        JsonNode mainResponse = json("""
            {
              "customerId": "cust-1",
              "currency": "USD",
              "items": [
                {"itemId": "A"},
                {"itemId": "B"}
              ]
            }
            """);

        when(mainClient.postMain(any(ObjectNode.class), any(DownstreamHeaders.class))).thenReturn(Mono.just(mainResponse));
        when(profileClient.postProfile(any(ObjectNode.class), any(DownstreamHeaders.class)))
            .thenReturn(Mono.error(new RuntimeException("profile down")));
        when(pricingClient.postPricing(any(ObjectNode.class), any(DownstreamHeaders.class)))
            .thenReturn(Mono.error(new RuntimeException("pricing down")));

        StepVerifier.create(aggregateService.aggregate(inboundRequest, headers))
            .assertNext(aggregated -> {
                ObjectNode root = (ObjectNode) aggregated;
                org.assertj.core.api.Assertions.assertThat(root.path("customerId").asString()).isEqualTo("cust-1");
                org.assertj.core.api.Assertions.assertThat(root.has("customerProfile")).isFalse();
                org.assertj.core.api.Assertions.assertThat(root.path("items").get(0).has("price")).isFalse();
            })
            .verifyComplete();

        JsonNode profileResponse = json("{\"tier\":\"GOLD\"}");
        JsonNode pricingResponse = json("""
            {
              "prices": [
                {"itemId": "A", "amount": 10.50},
                {"itemId": "B", "amount": 20.00}
              ]
            }
            """);

        when(profileClient.postProfile(any(ObjectNode.class), any(DownstreamHeaders.class)))
            .thenReturn(Mono.just(profileResponse));
        when(pricingClient.postPricing(any(ObjectNode.class), any(DownstreamHeaders.class)))
            .thenReturn(Mono.just(pricingResponse));

        StepVerifier.create(aggregateService.aggregate(inboundRequest, headers))
            .assertNext(aggregated -> {
                ObjectNode root = (ObjectNode) aggregated;
                org.assertj.core.api.Assertions.assertThat(root.path("customerProfile").path("tier").asString())
                    .isEqualTo("GOLD");
                org.assertj.core.api.Assertions.assertThat(root.path("items").get(0).path("price").decimalValue())
                    .isEqualByComparingTo("10.5");
                org.assertj.core.api.Assertions.assertThat(root.path("items").get(1).path("price").decimalValue())
                    .isEqualByComparingTo("20.0");
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

        when(mainClient.postMain(any(ObjectNode.class), any(DownstreamHeaders.class))).thenReturn(Mono.just(mainResponse));
        when(profileClient.postProfile(any(ObjectNode.class), any(DownstreamHeaders.class)))
            .thenReturn(Mono.just(profileResponse));

        StepVerifier.create(aggregateService.aggregate(inboundRequest, DownstreamHeaders.builder().build()))
            .assertNext(aggregated -> {
                ObjectNode root = (ObjectNode) aggregated;
                org.assertj.core.api.Assertions.assertThat(root.path("customerProfile").path("tier").asString())
                    .isEqualTo("GOLD");
                org.assertj.core.api.Assertions.assertThat(root.path("items").get(0).has("price")).isFalse();
            })
            .verifyComplete();

        verify(pricingClient, never()).postPricing(any(ObjectNode.class), any(DownstreamHeaders.class));
    }

    @Test
    void aggregate_rejectsUnknownRequestedPartsBeforeCallingMain() {
        ObjectNode inboundRequest = objectMapper.createObjectNode()
            .put("customerId", "cust-1");
        inboundRequest.putArray("include").add("unknown");

        StepVerifier.create(aggregateService.aggregate(inboundRequest, DownstreamHeaders.builder().build()))
            .expectErrorSatisfies(error -> org.assertj.core.api.Assertions.assertThat(error)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown"))
            .verify();

        verify(mainClient, never()).postMain(any(ObjectNode.class), any(DownstreamHeaders.class));
    }

    @Test
    void aggregate_rejectsNonStringRequestedPartsBeforeCallingMain() {
        ObjectNode inboundRequest = objectMapper.createObjectNode()
            .put("customerId", "cust-1");
        inboundRequest.putArray("include").add(42);

        StepVerifier.create(aggregateService.aggregate(inboundRequest, DownstreamHeaders.builder().build()))
            .expectErrorSatisfies(error -> org.assertj.core.api.Assertions.assertThat(error)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-blank strings"))
            .verify();

        verify(mainClient, never()).postMain(any(ObjectNode.class), any(DownstreamHeaders.class));
    }

    @Test
    void aggregate_skipsPricingEntriesWithNonDecimalAmount() {
        ObjectNode inboundRequest = objectMapper.createObjectNode()
            .put("customerId", "cust-1");
        inboundRequest.putArray("include").add("pricing");

        JsonNode mainResponse = json("""
            {
              "customerId": "cust-1",
              "items": [
                {"itemId": "A"},
                {"itemId": "B"}
              ]
            }
            """);
        JsonNode pricingResponse = json("""
            {
              "prices": [
                {"itemId": "A", "amount": "not-a-number"},
                {"itemId": "B", "amount": 20.00}
              ]
            }
            """);

        when(mainClient.postMain(any(ObjectNode.class), any(DownstreamHeaders.class))).thenReturn(Mono.just(mainResponse));
        when(pricingClient.postPricing(any(ObjectNode.class), any(DownstreamHeaders.class)))
            .thenReturn(Mono.just(pricingResponse));

        StepVerifier.create(aggregateService.aggregate(inboundRequest, DownstreamHeaders.builder().build()))
            .assertNext(aggregated -> {
                ObjectNode root = (ObjectNode) aggregated;
                org.assertj.core.api.Assertions.assertThat(root.path("items").get(0).has("price")).isFalse();
                org.assertj.core.api.Assertions.assertThat(root.path("items").get(1).path("price").decimalValue())
                    .isEqualByComparingTo("20.0");
            })
            .verifyComplete();

        verify(profileClient, never()).postProfile(any(ObjectNode.class), any(DownstreamHeaders.class));
    }

    private JsonNode json(String raw) {
        try {
            return objectMapper.readTree(raw);
        } catch (Exception ex) {
            throw new IllegalArgumentException(ex);
        }
    }
}
