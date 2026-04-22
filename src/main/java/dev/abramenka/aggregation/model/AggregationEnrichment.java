package dev.abramenka.aggregation.model;

import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

public interface AggregationEnrichment extends AggregationPart {

    Mono<JsonNode> fetch(AggregationContext context);

    void merge(ObjectNode root, JsonNode enrichmentResponse);

    @Override
    default Mono<AggregationPartResult> execute(ObjectNode rootSnapshot, AggregationContext context) {
        return fetch(context).map(response -> {
            ObjectNode workingRoot = rootSnapshot.deepCopy();
            merge(workingRoot, response);
            return AggregationPartResult.patch(name(), rootSnapshot, workingRoot);
        });
    }
}
