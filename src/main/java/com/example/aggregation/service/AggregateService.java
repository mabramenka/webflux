package com.example.aggregation.service;

import com.example.aggregation.enrichment.AggregationEnrichment;
import com.example.aggregation.client.ClientRequestContext;
import com.example.aggregation.client.AccountGroups;
import com.example.aggregation.model.AggregationContext;
import com.example.aggregation.model.EnrichmentFetchResult;
import com.example.aggregation.model.EnrichmentSelection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

@Service
@Slf4j
public class AggregateService {

    private static final String CUSTOMER_ID_FIELD = "customerId";

    private final AccountGroups accountGroupClient;
    private final List<AggregationEnrichment> enrichments;
    private final Map<String, AggregationEnrichment> enrichmentByName;

    public AggregateService(AccountGroups accountGroupClient, List<AggregationEnrichment> enrichments) {
        this.accountGroupClient = accountGroupClient;
        this.enrichments = List.copyOf(enrichments);
        this.enrichmentByName = buildEnrichmentIndex(enrichments);
    }

    public Mono<JsonNode> aggregate(ObjectNode inboundRequest, ClientRequestContext clientRequestContext) {
        return Mono.defer(() -> {
            EnrichmentSelection enrichmentSelection = EnrichmentSelection.from(inboundRequest);
            validateEnrichmentSelection(enrichmentSelection);

            ObjectNode accountGroupRequest = buildAccountGroupRequest(inboundRequest);

            return accountGroupClient.fetchAccountGroup(accountGroupRequest, clientRequestContext)
                .flatMap(accountGroupResponse -> {
                    AggregationContext context = new AggregationContext(
                        inboundRequest,
                        accountGroupResponse,
                        clientRequestContext,
                        enrichmentSelection
                    );

                    List<AggregationEnrichment> enabledEnrichments = enrichments.stream()
                        .filter(enrichment -> enrichmentSelection.includes(enrichment.name()))
                        .filter(enrichment -> enrichment.supports(context))
                        .toList();

                    ObjectNode root = (ObjectNode) accountGroupResponse.deepCopy();

                    return Flux.fromIterable(enabledEnrichments)
                        .flatMap(enrichment -> fetchEnrichment(enrichment, context))
                        .collectList()
                        .map(results -> merge(root, enabledEnrichments, results));
                });
        });
    }

    private ObjectNode buildAccountGroupRequest(ObjectNode inboundRequest) {
        ObjectNode request = JsonNodeFactory.instance.objectNode();
        request.put(CUSTOMER_ID_FIELD, inboundRequest.path(CUSTOMER_ID_FIELD).asString());
        request.put("market", inboundRequest.path("market").asString("US"));
        request.put("includeItems", inboundRequest.path("includeItems").asBoolean(true));
        return request;
    }

    private Mono<EnrichmentFetchResult> fetchEnrichment(AggregationEnrichment enrichment, AggregationContext context) {
        return enrichment.fetch(context)
            .map(response -> EnrichmentFetchResult.success(enrichment, response))
            .onErrorResume(ex -> {
                log.warn("Optional aggregation enrichment '{}' failed and will be skipped", enrichment.name(), ex);
                return Mono.just(EnrichmentFetchResult.failed(enrichment, ex));
            });
    }

    private JsonNode merge(ObjectNode root, List<AggregationEnrichment> enabledEnrichments, List<EnrichmentFetchResult> results) {
        Map<String, EnrichmentFetchResult> resultByName = results.stream()
            .collect(Collectors.toMap(EnrichmentFetchResult::name, Function.identity()));

        enabledEnrichments.stream()
            .map(enrichment -> resultByName.get(enrichment.name()))
            .filter(result -> result != null && result.successful())
            .forEach(result -> result.mergeInto(root));

        return root;
    }

    private void validateEnrichmentSelection(EnrichmentSelection enrichmentSelection) {
        if (enrichmentSelection.all()) {
            return;
        }

        List<String> unknownEnrichments = enrichmentSelection.names().stream()
            .filter(name -> !enrichmentByName.containsKey(name))
            .toList();

        if (!unknownEnrichments.isEmpty()) {
            throw new IllegalArgumentException("Unknown aggregation enrichment(s): " + String.join(", ", unknownEnrichments));
        }
    }

    private Map<String, AggregationEnrichment> buildEnrichmentIndex(List<AggregationEnrichment> registeredEnrichments) {
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
