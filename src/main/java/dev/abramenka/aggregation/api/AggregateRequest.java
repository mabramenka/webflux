package dev.abramenka.aggregation.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.jspecify.annotations.Nullable;

public record AggregateRequest(
        @NotEmpty @Size(max = 100) List<@NotBlank @Pattern(regexp = AggregateRequest.ID_PATTERN) String> ids,
        @Nullable List<@NotBlank String> include) {

    public static final String ID_PATTERN = "^[A-Za-z]{2}\\d{9}$";
}
