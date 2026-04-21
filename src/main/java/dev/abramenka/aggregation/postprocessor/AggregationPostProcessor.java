package dev.abramenka.aggregation.postprocessor;

import dev.abramenka.aggregation.model.AggregationContext;
import java.util.Set;
import reactor.core.publisher.Mono;
import tools.jackson.databind.node.ObjectNode;

public interface AggregationPostProcessor {

    String name();

    default Set<String> dependencies() {
        return Set.of();
    }

    Mono<Void> apply(ObjectNode root, AggregationContext context);
}
