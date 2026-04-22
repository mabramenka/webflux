package dev.abramenka.aggregation.model;

import java.util.Set;

public interface AggregationPart {

    String name();

    /**
     * Dependencies expand requested includes and order merge/apply phases. Enrichment fetches may still run
     * concurrently, so dependencies must not require another enrichment's fetch result.
     */
    default Set<String> dependencies() {
        return Set.of();
    }

    default boolean supports(AggregationContext context) {
        return true;
    }
}
