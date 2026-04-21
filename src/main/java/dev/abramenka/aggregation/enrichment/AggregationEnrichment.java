package dev.abramenka.aggregation.enrichment;

import dev.abramenka.aggregation.model.AggregationContext;
import dev.abramenka.aggregation.model.AggregationPart;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

public interface AggregationEnrichment extends AggregationPart {

    boolean supports(AggregationContext context);

    Mono<JsonNode> fetch(AggregationContext context);

    void merge(ObjectNode root, JsonNode enrichmentResponse);
}
