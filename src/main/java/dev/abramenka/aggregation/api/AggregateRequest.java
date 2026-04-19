package dev.abramenka.aggregation.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import org.jspecify.annotations.Nullable;

public record AggregateRequest(
        @NotEmpty List<@NotBlank String> ids, @Nullable List<@NotBlank String> include) {}
