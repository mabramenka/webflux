package dev.abramenka.aggregation.postprocessor;

import dev.abramenka.aggregation.model.AggregationContext;
import dev.abramenka.aggregation.model.AggregationPart;
import reactor.core.publisher.Mono;
import tools.jackson.databind.node.ObjectNode;

public interface AggregationPostProcessor extends AggregationPart {

    Mono<Void> apply(ObjectNode root, AggregationContext context);
}
