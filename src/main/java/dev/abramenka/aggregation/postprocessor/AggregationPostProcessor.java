package dev.abramenka.aggregation.postprocessor;

import dev.abramenka.aggregation.model.AggregationContext;
import reactor.core.publisher.Mono;
import tools.jackson.databind.node.ObjectNode;

public interface AggregationPostProcessor {

    String name();

    Mono<Void> apply(ObjectNode root, AggregationContext context);
}
