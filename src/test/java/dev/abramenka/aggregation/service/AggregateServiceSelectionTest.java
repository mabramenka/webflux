package dev.abramenka.aggregation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.abramenka.aggregation.api.AggregateRequest;
import dev.abramenka.aggregation.client.HttpServiceGroups;
import dev.abramenka.aggregation.error.DownstreamClientException;
import dev.abramenka.aggregation.error.ProblemCatalog;
import dev.abramenka.aggregation.error.RequestValidationException;
import dev.abramenka.aggregation.error.UnsupportedAggregationPartException;
import dev.abramenka.aggregation.model.ClientRequestContext;
import dev.abramenka.aggregation.model.ForwardedHeaders;
import dev.abramenka.aggregation.model.Projections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.codec.DecodingException;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

class AggregateServiceSelectionTest extends AggregateServiceTestSupport {

    @Test
    void aggregate_failsWhenSelectedEnrichmentFails_andMergesSuccessfulSelectedResults() {
        AggregateRequest request = new AggregateRequest(List.of("AB123456789"), List.of("account"));
        ClientRequestContext clientRequestContext = new ClientRequestContext(
                ForwardedHeaders.builder().authorization("Bearer token").build(), true, Projections.empty());

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

        when(accountGroupClient.fetchAccountGroup(any(ObjectNode.class), anyString(), any(ClientRequestContext.class)))
                .thenReturn(Mono.just(accountGroupResponse));
        when(accountClient.fetchAccounts(any(ObjectNode.class), anyString(), any(ClientRequestContext.class)))
                .thenReturn(Mono.error(new RuntimeException("account down")));

        StepVerifier.create(aggregateService.aggregate(request, clientRequestContext))
                .expectErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(DownstreamClientException.class)
                        .satisfies(ex -> assertThat(((DownstreamClientException) ex)
                                        .getBody()
                                        .getType())
                                .isEqualTo(ProblemCatalog.ENRICH_UNAVAILABLE.type())))
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

        when(accountClient.fetchAccounts(any(ObjectNode.class), anyString(), any(ClientRequestContext.class)))
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

        when(accountGroupClient.fetchAccountGroup(any(ObjectNode.class), anyString(), any(ClientRequestContext.class)))
                .thenReturn(Mono.just(accountGroupResponse));
        when(accountClient.fetchAccounts(any(ObjectNode.class), anyString(), any(ClientRequestContext.class)))
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

        verify(ownersClient, never()).fetchOwners(any(ObjectNode.class), anyString(), any(ClientRequestContext.class));

        ArgumentCaptor<ObjectNode> accountGroupRequestCaptor = ArgumentCaptor.forClass(ObjectNode.class);
        verify(accountGroupClient)
                .fetchAccountGroup(accountGroupRequestCaptor.capture(), anyString(), any(ClientRequestContext.class));
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
                        .hasMessage("One or more request fields failed validation."))
                .verify();

        verify(accountGroupClient, never())
                .fetchAccountGroup(any(ObjectNode.class), anyString(), any(ClientRequestContext.class));
    }

    @Test
    void aggregate_rejectsBlankPartSelectionBeforeCallingAccountGroup() {
        AggregateRequest request = new AggregateRequest(List.of("AB123456789"), List.of(" "));

        StepVerifier.create(aggregateService.aggregate(request, clientRequestContext()))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(RequestValidationException.class))
                .verify();

        verify(accountGroupClient, never())
                .fetchAccountGroup(any(ObjectNode.class), anyString(), any(ClientRequestContext.class));
    }

    @Test
    void aggregate_rejectsNonObjectAccountGroupResponse() {
        AggregateRequest request = new AggregateRequest(List.of("AB123456789"), null);
        JsonNode accountGroupResponse = json("""
            [
              {"id": "unexpected-array"}
            ]
            """);

        when(accountGroupClient.fetchAccountGroup(any(ObjectNode.class), anyString(), any(ClientRequestContext.class)))
                .thenReturn(Mono.just(accountGroupResponse));

        StepVerifier.create(aggregateService.aggregate(request, clientRequestContext()))
                .expectErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(DownstreamClientException.class)
                        .hasMessage("The main dependency payload does not satisfy the required contract."))
                .verify();

        verify(accountClient, never())
                .fetchAccounts(any(ObjectNode.class), anyString(), any(ClientRequestContext.class));
        verify(ownersClient, never()).fetchOwners(any(ObjectNode.class), anyString(), any(ClientRequestContext.class));
    }

    @Test
    void aggregate_rejectsEmptyAccountGroupResponse() {
        AggregateRequest request = new AggregateRequest(List.of("AB123456789"), List.of());

        when(accountGroupClient.fetchAccountGroup(any(ObjectNode.class), anyString(), any(ClientRequestContext.class)))
                .thenReturn(Mono.empty());

        StepVerifier.create(aggregateService.aggregate(request, clientRequestContext()))
                .expectErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(DownstreamClientException.class)
                        .satisfies(ex -> assertThat(((DownstreamClientException) ex)
                                        .getBody()
                                        .getType())
                                .isEqualTo(ProblemCatalog.MAIN_CONTRACT_VIOLATION.type())))
                .verify();
    }

    @Test
    void aggregate_softSkipsSelectedPartWhenMainPayloadHasNoRequiredKeys() {
        AggregateRequest request = new AggregateRequest(List.of("AB123456789"), List.of("account"));
        JsonNode accountGroupResponse = json("""
            {
              "customerId": "cust-1"
            }
            """);

        when(accountGroupClient.fetchAccountGroup(any(ObjectNode.class), anyString(), any(ClientRequestContext.class)))
                .thenReturn(Mono.just(accountGroupResponse));

        StepVerifier.create(aggregateService.aggregate(request, clientRequestContext()))
                .assertNext(aggregated -> {
                    JsonNode partMeta = aggregated.path("meta").path("parts").path("account");
                    assertThat(partMeta.path("status").asString()).isEqualTo("SKIPPED");
                    assertThat(partMeta.path("reason").asString()).isEqualTo("NO_KEYS_IN_MAIN");
                })
                .verifyComplete();

        assertPartMetric("account", "skipped", 1);
        verify(accountClient, never())
                .fetchAccounts(any(ObjectNode.class), anyString(), any(ClientRequestContext.class));
    }

    @Test
    void aggregate_softSkipsSelectedEnrichmentWhenDownstreamReturnsEmpty() {
        AggregateRequest request = new AggregateRequest(List.of("AB123456789"), List.of("account"));
        JsonNode accountGroupResponse = json("""
            {
              "customerId": "cust-1",
              "data": [
                {"accounts": [{"id": "acc-a"}]}
              ]
            }
            """);

        when(accountGroupClient.fetchAccountGroup(any(ObjectNode.class), anyString(), any(ClientRequestContext.class)))
                .thenReturn(Mono.just(accountGroupResponse));
        when(accountClient.fetchAccounts(any(ObjectNode.class), anyString(), any(ClientRequestContext.class)))
                .thenReturn(Mono.empty());

        StepVerifier.create(aggregateService.aggregate(request, clientRequestContext()))
                .assertNext(aggregated -> {
                    JsonNode partMeta = aggregated.path("meta").path("parts").path("account");
                    assertThat(partMeta.path("status").asString()).isEqualTo("EMPTY");
                    assertThat(partMeta.path("reason").asString()).isEqualTo("DOWNSTREAM_EMPTY");
                })
                .verifyComplete();

        assertPartMetric("account", "empty", 1);
    }

    @Test
    void aggregate_softSkipsSelectedEnrichmentWhenDownstreamResponds404() {
        AggregateRequest request = new AggregateRequest(List.of("AB123456789"), List.of("account"));
        JsonNode accountGroupResponse = json("""
            {
              "customerId": "cust-1",
              "data": [
                {"accounts": [{"id": "acc-a"}]}
              ]
            }
            """);
        DownstreamClientException notFound = DownstreamClientException.upstreamStatus(
                HttpServiceGroups.downstreamClientName(HttpServiceGroups.ACCOUNT), HttpStatus.NOT_FOUND);

        when(accountGroupClient.fetchAccountGroup(any(ObjectNode.class), anyString(), any(ClientRequestContext.class)))
                .thenReturn(Mono.just(accountGroupResponse));
        when(accountClient.fetchAccounts(any(ObjectNode.class), anyString(), any(ClientRequestContext.class)))
                .thenReturn(Mono.error(notFound));

        StepVerifier.create(aggregateService.aggregate(request, clientRequestContext()))
                .assertNext(aggregated -> {
                    JsonNode partMeta = aggregated.path("meta").path("parts").path("account");
                    assertThat(partMeta.path("status").asString()).isEqualTo("EMPTY");
                    assertThat(partMeta.path("reason").asString()).isEqualTo("DOWNSTREAM_NOT_FOUND");
                    assertThat(aggregated.path("data").path(0).has("account1")).isFalse();
                })
                .verifyComplete();

        assertPartMetric("account", "empty", 1);
    }

    @Test
    void aggregate_mapsUnreadableSelectedEnrichmentResponseToInvalidPayload() {
        AggregateRequest request = new AggregateRequest(List.of("AB123456789"), List.of("account"));
        JsonNode accountGroupResponse = json("""
            {
              "customerId": "cust-1",
              "data": [
                {"accounts": [{"id": "acc-a"}]}
              ]
            }
            """);

        when(accountGroupClient.fetchAccountGroup(any(ObjectNode.class), anyString(), any(ClientRequestContext.class)))
                .thenReturn(Mono.just(accountGroupResponse));
        when(accountClient.fetchAccounts(any(ObjectNode.class), anyString(), any(ClientRequestContext.class)))
                .thenReturn(Mono.error(new DecodingException("bad json")));

        StepVerifier.create(aggregateService.aggregate(request, clientRequestContext()))
                .assertNext(aggregated -> {
                    JsonNode partMeta = aggregated.path("meta").path("parts").path("account");
                    assertThat(partMeta.path("status").asString()).isEqualTo("FAILED");
                    assertThat(partMeta.path("criticality").asString()).isEqualTo("OPTIONAL");
                    assertThat(partMeta.path("reason").asString()).isEqualTo("INVALID_PAYLOAD");
                    assertThat(partMeta.path("errorCode").asString()).isEqualTo("ENRICH-INVALID-PAYLOAD");
                })
                .verifyComplete();
    }

    @Test
    void aggregate_softSkipsEnrichmentWhenFetchReturnsEmptyMono() {
        AggregateService service = aggregateServiceWith(emptyEnrichment());
        AggregateRequest request = new AggregateRequest(List.of("AB123456789"), null);
        JsonNode accountGroupResponse = json("""
            {
              "customerId": "cust-1"
            }
            """);

        when(accountGroupClient.fetchAccountGroup(any(ObjectNode.class), anyString(), any(ClientRequestContext.class)))
                .thenReturn(Mono.just(accountGroupResponse));

        StepVerifier.create(service.aggregate(request, clientRequestContext()))
                .assertNext(aggregated -> {
                    JsonNode partMeta = aggregated.path("meta").path("parts").path("empty");
                    assertThat(partMeta.path("status").asString()).isEqualTo("EMPTY");
                    assertThat(partMeta.path("reason").asString()).isEqualTo("DOWNSTREAM_EMPTY");
                })
                .verifyComplete();

        assertPartMetric("empty", "empty", 1);
        assertPartMetricMissing("empty", "failure");
    }
}
