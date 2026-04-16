package com.example.aggregation.service;

import com.example.aggregation.client.AccountGroups;
import com.example.aggregation.enrichment.AggregationEnrichment;
import com.example.aggregation.error.DownstreamClientException;
import com.example.aggregation.error.InvalidAggregationRequestException;
import com.example.aggregation.model.AggregationContext;
import com.example.aggregation.model.ClientRequestContext;
import com.example.aggregation.model.EnrichmentSelection;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

@Service
public class AggregateService {

    private static final String ACCOUNT_GROUP_CLIENT_NAME = "Account group";

    private final AccountGroups accountGroupClient;
    private final List<AggregationEnrichment> enrichments;
    private final Map<String, AggregationEnrichment> enrichmentByName;
    private final ObservationRegistry observationRegistry;
    private final AccountGroupRequestFactory accountGroupRequestFactory;
    private final EnrichmentExecutor enrichmentExecutor;
    private final AggregationMerger aggregationMerger;

    public AggregateService(
            AccountGroups accountGroupClient,
            List<AggregationEnrichment> enrichments,
            ObservationRegistry observationRegistry,
            AccountGroupRequestFactory accountGroupRequestFactory,
            EnrichmentExecutor enrichmentExecutor,
            AggregationMerger aggregationMerger) {
        this.accountGroupClient = accountGroupClient;
        this.enrichments = List.copyOf(enrichments);
        this.enrichmentByName = buildEnrichmentIndex(enrichments);
        this.observationRegistry = observationRegistry;
        this.accountGroupRequestFactory = accountGroupRequestFactory;
        this.enrichmentExecutor = enrichmentExecutor;
        this.aggregationMerger = aggregationMerger;
    }

    public Mono<JsonNode> aggregate(ObjectNode inboundRequest, ClientRequestContext clientRequestContext) {
        return Mono.defer(() -> {
            Observation observation = Observation.start("aggregation.request", observationRegistry);
            EnrichmentSelection enrichmentSelection = EnrichmentSelection.from(inboundRequest);
            validateEnrichmentSelection(enrichmentSelection);
            observation.lowCardinalityKeyValue("enrichment_selection", enrichmentSelection.all() ? "all" : "subset");
            observation.lowCardinalityKeyValue(
                    "requested_enrichments",
                    Integer.toString(enrichmentSelection.names().size()));

            ObjectNode accountGroupRequest = accountGroupRequestFactory.from(inboundRequest);

            return fetchAccountGroup(accountGroupRequest, clientRequestContext)
                    .flatMap(accountGroupResponse -> {
                        AggregationContext context = new AggregationContext(
                                inboundRequest, accountGroupResponse, clientRequestContext, enrichmentSelection);

                        List<AggregationEnrichment> enabledEnrichments = enrichments.stream()
                                .filter(enrichment -> enrichmentSelection.includes(enrichment.name()))
                                .filter(enrichment -> enrichment.supports(context))
                                .toList();

                        ObjectNode root =
                                aggregationMerger.mutableRoot(ACCOUNT_GROUP_CLIENT_NAME, accountGroupResponse);

                        return Flux.fromIterable(enabledEnrichments)
                                .flatMap(enrichment -> enrichmentExecutor.fetch(enrichment, context))
                                .collectList()
                                .map(results -> aggregationMerger.merge(root, enabledEnrichments, results));
                    })
                    .contextWrite(context -> context.put(ObservationThreadLocalAccessor.KEY, observation))
                    .doOnError(observation::error)
                    .doFinally(signalType -> observation.stop());
        });
    }

    private Mono<JsonNode> fetchAccountGroup(
            ObjectNode accountGroupRequest, ClientRequestContext clientRequestContext) {
        return accountGroupClient
                .fetchAccountGroup(accountGroupRequest, clientRequestContext)
                .onErrorMap(
                        ex -> !(ex instanceof DownstreamClientException),
                        ex -> DownstreamClientException.gatewayError(
                                ACCOUNT_GROUP_CLIENT_NAME, "account group client returned an unreadable response", ex));
    }

    private void validateEnrichmentSelection(EnrichmentSelection enrichmentSelection) {
        if (enrichmentSelection.all()) {
            return;
        }

        List<String> unknownEnrichments = enrichmentSelection.names().stream()
                .filter(name -> !enrichmentByName.containsKey(name))
                .toList();

        if (!unknownEnrichments.isEmpty()) {
            throw new InvalidAggregationRequestException(
                    "Unknown aggregation enrichment(s): " + String.join(", ", unknownEnrichments));
        }
    }

    private static Map<String, AggregationEnrichment> buildEnrichmentIndex(
            List<AggregationEnrichment> registeredEnrichments) {
        Map<String, AggregationEnrichment> index = new LinkedHashMap<>();
        registeredEnrichments.forEach(enrichment -> {
            AggregationEnrichment previous = index.putIfAbsent(enrichment.name(), enrichment);
            if (previous != null) {
                throw new IllegalStateException("Duplicate aggregation enrichment name: " + enrichment.name());
            }
        });
        return Map.copyOf(index);
    }
}
