package com.example.aggregation.client;

import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.util.MultiValueMap;
import org.springframework.web.server.ResponseStatusException;

public record ClientRequestContext(
    ForwardedHeaders headers,
    @Nullable Boolean detokenize
) {

    private static final String DETOKENIZE_QUERY_PARAM = "detokenize";

    public static ClientRequestContext from(HttpHeaders headers, MultiValueMap<String, String> queryParams) {
        return new ClientRequestContext(
            ForwardedHeaders.from(headers),
            booleanQueryParam(queryParams, DETOKENIZE_QUERY_PARAM).orElse(null)
        );
    }

    private static Optional<Boolean> booleanQueryParam(MultiValueMap<String, String> queryParams, String name) {
        String rawValue = queryParams.getFirst(name);
        if (rawValue == null || rawValue.isBlank()) {
            return Optional.empty();
        }
        if ("true".equalsIgnoreCase(rawValue)) {
            return Optional.of(true);
        }
        if ("false".equalsIgnoreCase(rawValue)) {
            return Optional.of(false);
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "'" + name + "' must be either true or false");
    }
}
