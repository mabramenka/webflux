package com.example.aggregation.client;

import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Builder;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;

@Builder
public record ForwardedHeaders(
        @Nullable String authorization,
        @Nullable String requestId,
        @Nullable String correlationId,
        @Nullable String acceptLanguage) {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

    public Map<String, String> asMap() {
        Map<String, String> headers = new LinkedHashMap<>();
        putIfPresent(headers, HttpHeaders.AUTHORIZATION, authorization);
        putIfPresent(headers, REQUEST_ID_HEADER, requestId);
        putIfPresent(headers, CORRELATION_ID_HEADER, correlationId);
        putIfPresent(headers, HttpHeaders.ACCEPT_LANGUAGE, acceptLanguage);
        return headers;
    }

    private static void putIfPresent(Map<String, String> target, String name, @Nullable String value) {
        if (value != null && !value.isBlank()) {
            target.put(name, value);
        }
    }
}
