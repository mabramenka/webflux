package dev.abramenka.aggregation.error;

import dev.abramenka.aggregation.model.ForwardedHeaders;
import dev.abramenka.aggregation.model.TraceContext;
import java.net.URI;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.web.server.ServerWebExchange;

final class ProblemResponseSupport {

    private static final String TIMESTAMP_PROPERTY = "timestamp";
    private static final String TRACE_ID_PROPERTY = "traceId";

    private ProblemResponseSupport() {}

    static ProblemCatalog catalogForFrameworkStatus(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> ProblemCatalog.CLIENT_VALIDATION;
            case 401 -> ProblemCatalog.CLIENT_UNAUTHENTICATED;
            case 403 -> ProblemCatalog.CLIENT_FORBIDDEN;
            case 404 -> ProblemCatalog.CLIENT_NOT_FOUND;
            case 405 -> ProblemCatalog.CLIENT_METHOD_NOT_ALLOWED;
            case 406 -> ProblemCatalog.CLIENT_NOT_ACCEPTABLE;
            case 415 -> ProblemCatalog.CLIENT_UNSUPPORTED_MEDIA;
            case 429 -> ProblemCatalog.CLIENT_RATE_LIMITED;
            default -> ProblemCatalog.PLATFORM_INTERNAL;
        };
    }

    static void enrich(ProblemDetail detail, ServerWebExchange exchange) {
        String traceId = traceId(exchange);
        detail.setProperty(TRACE_ID_PROPERTY, traceId);
        detail.setProperty(TIMESTAMP_PROPERTY, Instant.now().toString());
        detail.setInstance(URI.create("/requests/" + uriSafe(traceId)));
    }

    static HttpHeaders responseHeaders(@Nullable HttpHeaders headers, ServerWebExchange exchange) {
        HttpHeaders responseHeaders = new HttpHeaders();
        if (headers != null) {
            responseHeaders.putAll(headers);
        }
        putIfPresent(responseHeaders, TraceContext.TRACEPARENT_HEADER, traceparent(exchange));
        return responseHeaders;
    }

    private static String traceId(ServerWebExchange exchange) {
        String traceId = TraceContext.traceIdFromTraceparent(traceparent(exchange));
        if (traceId != null) {
            return traceId;
        }
        String requestId = requestId(exchange);
        return requestId != null ? requestId : TraceContext.newTraceId();
    }

    private static String traceparent(ServerWebExchange exchange) {
        String requestTraceparent =
                firstNonBlank(exchange.getRequest().getHeaders().getFirst(TraceContext.TRACEPARENT_HEADER));
        if (requestTraceparent != null && TraceContext.traceIdFromTraceparent(requestTraceparent) != null) {
            return requestTraceparent;
        }
        String responseTraceparent =
                firstNonBlank(exchange.getResponse().getHeaders().getFirst(TraceContext.TRACEPARENT_HEADER));
        if (responseTraceparent != null && TraceContext.traceIdFromTraceparent(responseTraceparent) != null) {
            return responseTraceparent;
        }
        String generated = TraceContext.newTraceparent();
        exchange.getResponse().getHeaders().set(TraceContext.TRACEPARENT_HEADER, generated);
        return generated;
    }

    private static @Nullable String requestId(ServerWebExchange exchange) {
        String responseHeader = exchange.getResponse().getHeaders().getFirst(ForwardedHeaders.REQUEST_ID_HEADER);
        if (responseHeader != null && !responseHeader.isBlank()) {
            return responseHeader;
        }
        String requestHeader = exchange.getRequest().getHeaders().getFirst(ForwardedHeaders.REQUEST_ID_HEADER);
        if (requestHeader != null && !requestHeader.isBlank()) {
            return requestHeader;
        }
        return null;
    }

    private static String uriSafe(String value) {
        return value.replaceAll("[^A-Za-z0-9._~-]", "-");
    }

    private static @Nullable String firstNonBlank(@Nullable String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static void putIfPresent(HttpHeaders headers, String name, @Nullable String value) {
        if (value != null && !value.isBlank()) {
            headers.set(name, value);
        }
    }
}
