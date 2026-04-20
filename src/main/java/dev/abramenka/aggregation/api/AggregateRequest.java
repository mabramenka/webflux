package dev.abramenka.aggregation.api;

import dev.abramenka.aggregation.model.AccountGroupIds;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.jspecify.annotations.Nullable;

public record AggregateRequest(
        @NotEmpty @Size(max = AccountGroupIds.MAX_PER_REQUEST)
        List<@NotBlank @Pattern(regexp = AccountGroupIds.PATTERN) String> ids,

        @Nullable @Size(max = 32) List<@NotBlank String> include) {}
