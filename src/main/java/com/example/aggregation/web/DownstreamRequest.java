package com.example.aggregation.web;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.util.MultiValueMap;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriBuilder;

public record DownstreamRequest(
    DownstreamHeaders headers,
    Boolean detokenize
) {

    private static final String DETOKENIZE = "detokenize";

    public static DownstreamRequest from(HttpHeaders headers, MultiValueMap<String, String> queryParams) {
        return new DownstreamRequest(
            DownstreamHeaders.from(headers),
            booleanQueryParam(queryParams, DETOKENIZE)
        );
    }

    public UriBuilder applyQueryParams(UriBuilder builder) {
        if (detokenize != null) {
            builder.queryParam(DETOKENIZE, detokenize);
        }
        return builder;
    }

    private static Boolean booleanQueryParam(MultiValueMap<String, String> queryParams, String name) {
        String rawValue = queryParams.getFirst(name);
        if (rawValue == null || rawValue.isBlank()) {
            return null;
        }
        if ("true".equalsIgnoreCase(rawValue)) {
            return true;
        }
        if ("false".equalsIgnoreCase(rawValue)) {
            return false;
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "'" + name + "' must be either true or false");
    }
}
