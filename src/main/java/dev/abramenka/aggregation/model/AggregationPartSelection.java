package dev.abramenka.aggregation.model;

import dev.abramenka.aggregation.error.RequestValidationException;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

public record AggregationPartSelection(boolean all, Set<String> names) {

    public static AggregationPartSelection from(@Nullable List<String> include) {
        if (include == null) {
            return new AggregationPartSelection(true, Set.of());
        }

        Set<String> requested = new LinkedHashSet<>();
        for (String name : include) {
            String trimmed = name.trim();
            if (trimmed.isBlank()) {
                throw RequestValidationException.invalidRequestValue(
                        "include", "'include' values must be non-blank strings");
            }
            requested.add(trimmed);
        }

        return subset(requested);
    }

    public static AggregationPartSelection subset(Set<String> names) {
        return new AggregationPartSelection(false, Collections.unmodifiableSet(new LinkedHashSet<>(names)));
    }

    public boolean includes(String name) {
        return all || names.contains(name);
    }
}
