package com.example.aggregation.application;

import com.example.aggregation.application.enrichment.AggregationEnrichment;
import com.example.aggregation.downstream.DownstreamRequest;
import com.example.aggregation.downstream.WebClientMainClient;
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

    private final WebClientMainClient mainClient;
    private final List<AggregationEnrichment> enrichments;
    private final Map<String, AggregationEnrichment> enrichmentByName;

    public AggregateService(WebClientMainClient mainClient, List<AggregationEnrichment> enrichments) {
        this.mainClient = mainClient;
        this.enrichments = List.copyOf(enrichments);
        this.enrichmentByName = buildEnrichmentIndex(enrichments);
    }

    public Mono<JsonNode> aggregate(ObjectNode inboundRequest, DownstreamRequest downstreamRequest) {
        return Mono.defer(() -> {
            RequestedEnrichments requestedEnrichments = RequestedEnrichments.from(inboundRequest);
            validateRequestedEnrichments(requestedEnrichments);

            ObjectNode mainRequest = buildMainRequest(inboundRequest);

            return mainClient.postMain(mainRequest, downstreamRequest)
                .flatMap(mainResponse -> {
                    AggregationContext context = new AggregationContext(
                        inboundRequest,
                        mainResponse,
                        downstreamRequest,
                        requestedEnrichments
                    );

                    List<AggregationEnrichment> enabledEnrichments = enrichments.stream()
                        .filter(enrichment -> requestedEnrichments.includes(enrichment.name()))
                        .filter(enrichment -> enrichment.supports(context))
                        .toList();

                    ObjectNode root = (ObjectNode) mainResponse.deepCopy();

                    return Flux.fromIterable(enabledEnrichments)
                        .flatMap(enrichment -> fetchEnrichment(enrichment, context))
                        .collectList()
                        .map(results -> merge(root, enabledEnrichments, results));
                });
        });
    }

    private ObjectNode buildMainRequest(ObjectNode inboundRequest) {
        ObjectNode request = JsonNodeFactory.instance.objectNode();
        request.put(CUSTOMER_ID_FIELD, inboundRequest.path(CUSTOMER_ID_FIELD).asString());
        request.put("market", inboundRequest.path("market").asString("US"));
        request.put("includeItems", inboundRequest.path("includeItems").asBoolean(true));
        return request;
    }

    private Mono<EnrichmentResult> fetchEnrichment(AggregationEnrichment enrichment, AggregationContext context) {
        return enrichment.fetch(context)
            .map(response -> EnrichmentResult.success(enrichment, response))
            .onErrorResume(ex -> {
                log.warn("Optional aggregation enrichment '{}' failed and will be skipped", enrichment.name(), ex);
                return Mono.just(EnrichmentResult.failed(enrichment, ex));
            });
    }

    private JsonNode merge(ObjectNode root, List<AggregationEnrichment> enabledEnrichments, List<EnrichmentResult> results) {
        Map<String, EnrichmentResult> resultByName = results.stream()
            .collect(Collectors.toMap(EnrichmentResult::name, Function.identity()));

        enabledEnrichments.stream()
            .map(enrichment -> resultByName.get(enrichment.name()))
            .filter(result -> result != null && result.successful())
            .forEach(result -> result.mergeInto(root));

        return root;
    }

    private void validateRequestedEnrichments(RequestedEnrichments requestedEnrichments) {
        if (requestedEnrichments.all()) {
            return;
        }

        List<String> unknownEnrichments = requestedEnrichments.names().stream()
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
