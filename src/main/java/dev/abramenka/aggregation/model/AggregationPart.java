package dev.abramenka.aggregation.model;

import java.util.Set;

public interface AggregationPart {

    String name();

    default Set<String> dependencies() {
        return Set.of();
    }
}
