package dev.abramenka.aggregation.model;

import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;

public record ForwardedHeaders(
        @Nullable String authorization,
        @Nullable String requestId,
        @Nullable String correlationId,
        @Nullable String acceptLanguage) {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

    public static Builder builder() {
        return new Builder();
    }

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

    public static final class Builder {
        private @Nullable String authorization;
        private @Nullable String requestId;
        private @Nullable String correlationId;
        private @Nullable String acceptLanguage;

        private Builder() {}

        public Builder authorization(@Nullable String authorization) {
            this.authorization = authorization;
            return this;
        }

        public Builder requestId(@Nullable String requestId) {
            this.requestId = requestId;
            return this;
        }

        public Builder correlationId(@Nullable String correlationId) {
            this.correlationId = correlationId;
            return this;
        }

        public Builder acceptLanguage(@Nullable String acceptLanguage) {
            this.acceptLanguage = acceptLanguage;
            return this;
        }

        public ForwardedHeaders build() {
            return new ForwardedHeaders(authorization, requestId, correlationId, acceptLanguage);
        }
    }
}
