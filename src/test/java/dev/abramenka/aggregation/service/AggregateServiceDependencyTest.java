package dev.abramenka.aggregation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.abramenka.aggregation.api.AggregateRequest;
import dev.abramenka.aggregation.enrichment.account.AccountEnrichmentTestFactory;
import dev.abramenka.aggregation.enrichment.owners.OwnersEnrichmentTestFactory;
import dev.abramenka.aggregation.error.OrchestrationException;
import dev.abramenka.aggregation.model.AggregationContext;
import dev.abramenka.aggregation.model.AggregationEnrichment;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

class AggregateServiceDependencyTest extends AggregateServiceTestSupport {

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

        when(accountGroupClient.fetchAccountGroup(any(ObjectNode.class), anyString(), any()))
                .thenReturn(Mono.just(accountGroupResponse));
        when(ownersClient.fetchOwners(any(ObjectNode.class), anyString(), any()))
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
        verify(accountClient, never()).fetchAccounts(any(ObjectNode.class), anyString(), any());
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

        when(accountGroupClient.fetchAccountGroup(any(ObjectNode.class), anyString(), any()))
                .thenReturn(Mono.just(accountGroupResponse));

        StepVerifier.create(service.aggregate(request, clientRequestContext()))
                .expectErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(OrchestrationException.class)
                        .hasMessage("The service detected an internal aggregation invariant violation."))
                .verify();

        assertPartMetric("auditTrail", "failure", 1);
    }

    @Test
    void aggregate_softSkipsSelectedEnrichmentWhenUnsupportedByCurrentPayload() {
        AggregateService service = aggregateServiceWith(List.of(unsupportedEnrichment("auditTrail")));
        AggregateRequest request = new AggregateRequest(List.of("AB123456789"), List.of("auditTrail"));
        JsonNode accountGroupResponse = json("""
            {
              "customerId": "cust-1"
            }
            """);

        when(accountGroupClient.fetchAccountGroup(any(ObjectNode.class), anyString(), any()))
                .thenReturn(Mono.just(accountGroupResponse));

        StepVerifier.create(service.aggregate(request, clientRequestContext()))
                .assertNext(aggregated -> {
                    JsonNode partMeta = aggregated.path("meta").path("parts").path("auditTrail");
                    assertThat(partMeta.path("status").asString()).isEqualTo("SKIPPED");
                    assertThat(partMeta.path("reason").asString()).isEqualTo("UNSUPPORTED_CONTEXT");
                })
                .verifyComplete();

        assertPartMetric("auditTrail", "skipped", 1);
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

        when(accountGroupClient.fetchAccountGroup(any(ObjectNode.class), anyString(), any()))
                .thenReturn(Mono.just(accountGroupResponse));
        when(accountClient.fetchAccounts(any(ObjectNode.class), anyString(), any()))
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

        verify(ownersClient, never()).fetchOwners(any(ObjectNode.class), anyString(), any());
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

        when(accountGroupClient.fetchAccountGroup(any(ObjectNode.class), anyString(), any()))
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

        when(accountGroupClient.fetchAccountGroup(any(ObjectNode.class), anyString(), any()))
                .thenReturn(Mono.just(accountGroupResponse));

        StepVerifier.create(service.aggregate(request, clientRequestContext()))
                .assertNext(aggregated -> {
                    assertThat(aggregated.path("enriched").asBoolean()).isTrue();
                    assertThat(aggregated.path("audited").asBoolean()).isTrue();
                })
                .verifyComplete();
    }

    @Test
    void aggregate_appliesSameLevelResultsInGraphOrderRegardlessOfFetchCompletionOrder() {
        AggregationEnrichment slowFirst = sharedFieldEnrichment("alpha", "winner", "alpha", Duration.ofMillis(100));
        AggregationEnrichment fastSecond = sharedFieldEnrichment("bravo", "winner", "bravo", Duration.ZERO);
        AggregateService service = aggregateServiceWith(List.of(slowFirst, fastSecond));
        AggregateRequest request = new AggregateRequest(List.of("AB123456789"), List.of("alpha", "bravo"));
        JsonNode accountGroupResponse = json("""
            {
              "customerId": "cust-1"
            }
            """);

        when(accountGroupClient.fetchAccountGroup(any(ObjectNode.class), anyString(), any()))
                .thenReturn(Mono.just(accountGroupResponse));

        StepVerifier.create(service.aggregate(request, clientRequestContext()))
                .assertNext(aggregated ->
                        assertThat(aggregated.path("winner").asString()).isEqualTo("bravo"))
                .verifyComplete();
    }

    private AggregationEnrichment sharedFieldEnrichment(String name, String field, String value, Duration fetchDelay) {
        return new AggregationEnrichment() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Mono<JsonNode> fetch(AggregationContext context) {
                Mono<JsonNode> response = Mono.just(objectMapper.createObjectNode());
                return fetchDelay.isZero() ? response : response.delayElement(fetchDelay);
            }

            @Override
            public void merge(ObjectNode root, JsonNode enrichmentResponse) {
                root.put(field, value);
            }
        };
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

        when(accountGroupClient.fetchAccountGroup(any(ObjectNode.class), anyString(), any()))
                .thenReturn(Mono.just(accountGroupResponse));

        StepVerifier.create(service.aggregate(request, clientRequestContext()))
                .expectErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(OrchestrationException.class)
                        .hasMessage("The service could not assemble the aggregated response."))
                .verify();

        assertPartMetric("mergeFailure", "failure", 1);
    }
}
