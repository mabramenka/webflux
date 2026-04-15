package com.example.aggregation.downstream;

import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.util.MultiValueMap;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriBuilder;

public record DownstreamRequest(
    DownstreamHeaders headers,
    Boolean detokenize
) {

    private static final String DETOKENIZE_QUERY_PARAM = "detokenize";

    public static DownstreamRequest from(HttpHeaders headers, MultiValueMap<String, String> queryParams) {
        return new DownstreamRequest(
            DownstreamHeaders.from(headers),
            booleanQueryParam(queryParams, DETOKENIZE_QUERY_PARAM).orElse(null)
        );
    }

    public UriBuilder applyQueryParams(UriBuilder builder) {
        if (detokenize != null) {
            builder.queryParam(DETOKENIZE_QUERY_PARAM, detokenize);
        }
        return builder;
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
