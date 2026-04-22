package dev.abramenka.aggregation.model;

import java.util.Set;
import reactor.core.publisher.Mono;
import tools.jackson.databind.node.ObjectNode;

public interface AggregationPart {

    String name();

    /**
     * Dependencies expand requested includes and order execution levels. Parts in the same level run concurrently;
     * dependent parts run only after dependency results are applied to the root document.
     */
    default Set<String> dependencies() {
        return Set.of();
    }

    default boolean supports(AggregationContext context) {
        return true;
    }

    Mono<AggregationPartResult> execute(ObjectNode rootSnapshot, AggregationContext context);
}
