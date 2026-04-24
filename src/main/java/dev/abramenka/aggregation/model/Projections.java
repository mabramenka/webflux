package dev.abramenka.aggregation.model;

import java.util.Arrays;
import java.util.List;
import org.jspecify.annotations.Nullable;

public record Projections(List<String> raw) {

    private static final Projections EMPTY = new Projections(List.of());

    public Projections {
        raw = List.copyOf(raw);
    }

    public static Projections empty() {
        return EMPTY;
    }

    public static Projections parse(@Nullable String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return EMPTY;
        }
        List<String> tokens = Arrays.stream(rawValue.split(","))
                .map(String::trim)
                .filter(token -> !token.isEmpty())
                .toList();
        return tokens.isEmpty() ? EMPTY : new Projections(tokens);
    }

    public boolean isEmpty() {
        return raw.isEmpty();
    }

    public String asQueryValue() {
        return String.join(",", raw);
    }

    public String orDefault(String defaultFields) {
        return isEmpty() ? defaultFields : asQueryValue();
    }
}
