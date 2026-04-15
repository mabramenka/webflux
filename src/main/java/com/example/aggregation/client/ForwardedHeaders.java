package com.example.aggregation.client;

import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Builder;
import org.springframework.http.HttpHeaders;

@Builder
public record ForwardedHeaders(
    String authorization,
    String requestId,
    String correlationId,
    String acceptLanguage
) {

    public static ForwardedHeaders from(HttpHeaders inbound) {
        return ForwardedHeaders.builder()
            .authorization(inbound.getFirst(HttpHeaders.AUTHORIZATION))
            .requestId(inbound.getFirst("X-Request-Id"))
            .correlationId(inbound.getFirst("X-Correlation-Id"))
            .acceptLanguage(inbound.getFirst(HttpHeaders.ACCEPT_LANGUAGE))
            .build();
    }

    public Map<String, String> asMap() {
        Map<String, String> headers = new LinkedHashMap<>();
        putIfPresent(headers, HttpHeaders.AUTHORIZATION, authorization);
        putIfPresent(headers, "X-Request-Id", requestId);
        putIfPresent(headers, "X-Correlation-Id", correlationId);
        putIfPresent(headers, HttpHeaders.ACCEPT_LANGUAGE, acceptLanguage);
        return headers;
    }

    private static void putIfPresent(Map<String, String> target, String name, String value) {
        if (value != null && !value.isBlank()) {
            target.put(name, value);
        }
    }
}
