package dev.abramenka.aggregation.workflow.binding;

import java.util.Objects;

/** Non-blank identifier for a {@link DownstreamBinding} within an aggregation part. */
public record BindingName(String value) {

    public BindingName {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("Binding name must not be blank");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
