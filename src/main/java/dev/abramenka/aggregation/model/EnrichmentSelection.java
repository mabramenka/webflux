package dev.abramenka.aggregation.model;

import dev.abramenka.aggregation.error.InvalidAggregationRequestException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

public record EnrichmentSelection(boolean all, Set<String> names) {

    public static EnrichmentSelection from(@Nullable List<String> include) {
        if (include == null) {
            return new EnrichmentSelection(true, Set.of());
        }

        Set<String> requested = new LinkedHashSet<>();
        for (String name : include) {
            String trimmed = name.trim();
            if (trimmed.isBlank()) {
                throw new InvalidAggregationRequestException("'include' values must be non-blank strings");
            }
            requested.add(trimmed);
        }

        return new EnrichmentSelection(false, Set.copyOf(requested));
    }

    public boolean includes(String name) {
        return all || names.contains(name);
    }
}
