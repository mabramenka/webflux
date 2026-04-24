package dev.abramenka.aggregation.model;

import java.util.Set;
import reactor.core.publisher.Mono;

public interface AggregationPart {

    String name();

    /**
     * Dependencies expand requested includes and order execution levels. Parts in the same level run concurrently;
     * dependent parts run only after dependency results are successfully applied to the root document.
     */
    default Set<String> dependencies() {
        return Set.of();
    }

    default boolean supports(AggregationContext context) {
        return true;
    }

    Mono<AggregationPartResult> execute(AggregationContext context);
}
