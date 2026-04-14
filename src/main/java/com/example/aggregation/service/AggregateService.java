package com.example.aggregation.service;

import com.example.aggregation.client.MainClient;
import com.example.aggregation.web.DownstreamRequest;
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

    private final MainClient mainClient;
    private final List<AggregationPart> parts;
    private final Map<String, AggregationPart> partByName;

    public AggregateService(MainClient mainClient, List<AggregationPart> parts) {
        this.mainClient = mainClient;
        this.parts = List.copyOf(parts);
        this.partByName = buildPartIndex(parts);
    }

    public Mono<JsonNode> aggregate(ObjectNode inboundRequest, DownstreamRequest downstreamRequest) {
        return Mono.defer(() -> {
            RequestedParts requestedParts = RequestedParts.from(inboundRequest);
            validateRequestedParts(requestedParts);

            ObjectNode mainRequest = buildMainRequest(inboundRequest);

            return mainClient.postMain(mainRequest, downstreamRequest)
                .flatMap(mainResponse -> {
                    AggregationContext context = new AggregationContext(
                        inboundRequest,
                        mainResponse,
                        downstreamRequest,
                        requestedParts
                    );

                    List<AggregationPart> enabledParts = parts.stream()
                        .filter(part -> requestedParts.includes(part.name()))
                        .filter(part -> part.supports(context))
                        .toList();

                    ObjectNode root = (ObjectNode) mainResponse.deepCopy();

                    return Flux.fromIterable(enabledParts)
                        .flatMap(part -> fetchPart(part, context))
                        .collectList()
                        .map(results -> merge(root, enabledParts, results));
                });
        });
    }

    private ObjectNode buildMainRequest(ObjectNode inboundRequest) {
        ObjectNode request = JsonNodeFactory.instance.objectNode();
        request.put("customerId", inboundRequest.path("customerId").asString());
        request.put("market", inboundRequest.path("market").asString("US"));
        request.put("includeItems", inboundRequest.path("includeItems").asBoolean(true));
        return request;
    }

    private Mono<PartResult> fetchPart(AggregationPart part, AggregationContext context) {
        return part.fetch(context)
            .map(response -> PartResult.success(part, response))
            .onErrorResume(ex -> {
                log.warn("Optional aggregation part '{}' failed and will be skipped", part.name(), ex);
                return Mono.just(PartResult.failed(part, ex));
            });
    }

    private JsonNode merge(ObjectNode root, List<AggregationPart> enabledParts, List<PartResult> results) {
        Map<String, PartResult> resultByName = results.stream()
            .collect(Collectors.toMap(PartResult::name, Function.identity()));

        enabledParts.stream()
            .map(part -> resultByName.get(part.name()))
            .filter(result -> result != null && result.successful())
            .forEach(result -> result.mergeInto(root));

        return root;
    }

    private void validateRequestedParts(RequestedParts requestedParts) {
        if (requestedParts.all()) {
            return;
        }

        List<String> unknownParts = requestedParts.names().stream()
            .filter(name -> !partByName.containsKey(name))
            .toList();

        if (!unknownParts.isEmpty()) {
            throw new IllegalArgumentException("Unknown aggregation part(s): " + String.join(", ", unknownParts));
        }
    }

    private Map<String, AggregationPart> buildPartIndex(List<AggregationPart> registeredParts) {
        Map<String, AggregationPart> index = new LinkedHashMap<>();
        registeredParts.forEach(part -> {
            AggregationPart previous = index.putIfAbsent(part.name(), part);
            if (previous != null) {
                throw new IllegalStateException("Duplicate aggregation part name: " + part.name());
            }
        });
        return Map.copyOf(index);
    }
}
