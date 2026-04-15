package com.example.aggregation.client;

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

    public void applyTo(HttpHeaders target) {
        setIfPresent(target, HttpHeaders.AUTHORIZATION, authorization);
        setIfPresent(target, "X-Request-Id", requestId);
        setIfPresent(target, "X-Correlation-Id", correlationId);
        setIfPresent(target, HttpHeaders.ACCEPT_LANGUAGE, acceptLanguage);
    }

    private static void setIfPresent(HttpHeaders target, String name, String value) {
        if (value != null && !value.isBlank()) {
            target.set(name, value);
        }
    }
}
