package dev.abramenka.aggregation.error;

import static org.assertj.core.api.Assertions.assertThat;

import dev.abramenka.aggregation.model.ForwardedHeaders;
import dev.abramenka.aggregation.model.TraceContext;
import java.lang.reflect.Method;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

class ProblemResponseSupportTest {

    @Test
    void catalogForFrameworkStatus_mapsKnownAndFallbackStatuses() {
        assertThat(ProblemResponseSupport.catalogForFrameworkStatus(HttpStatusCode.valueOf(400)))
                .isEqualTo(ProblemCatalog.CLIENT_VALIDATION);
        assertThat(ProblemResponseSupport.catalogForFrameworkStatus(HttpStatusCode.valueOf(401)))
                .isEqualTo(ProblemCatalog.CLIENT_UNAUTHENTICATED);
        assertThat(ProblemResponseSupport.catalogForFrameworkStatus(HttpStatusCode.valueOf(403)))
                .isEqualTo(ProblemCatalog.CLIENT_FORBIDDEN);
        assertThat(ProblemResponseSupport.catalogForFrameworkStatus(HttpStatusCode.valueOf(404)))
                .isEqualTo(ProblemCatalog.CLIENT_NOT_FOUND);
        assertThat(ProblemResponseSupport.catalogForFrameworkStatus(HttpStatusCode.valueOf(405)))
                .isEqualTo(ProblemCatalog.CLIENT_METHOD_NOT_ALLOWED);
        assertThat(ProblemResponseSupport.catalogForFrameworkStatus(HttpStatusCode.valueOf(406)))
                .isEqualTo(ProblemCatalog.CLIENT_NOT_ACCEPTABLE);
        assertThat(ProblemResponseSupport.catalogForFrameworkStatus(HttpStatusCode.valueOf(415)))
                .isEqualTo(ProblemCatalog.CLIENT_UNSUPPORTED_MEDIA);
        assertThat(ProblemResponseSupport.catalogForFrameworkStatus(HttpStatusCode.valueOf(429)))
                .isEqualTo(ProblemCatalog.CLIENT_RATE_LIMITED);
        assertThat(ProblemResponseSupport.catalogForFrameworkStatus(HttpStatusCode.valueOf(500)))
                .isEqualTo(ProblemCatalog.PLATFORM_INTERNAL);
        assertThat(ProblemResponseSupport.catalogForFrameworkStatus(HttpStatusCode.valueOf(418)))
                .isEqualTo(ProblemCatalog.PLATFORM_INTERNAL);
    }

    @Test
    void enrich_usesValidRequestTraceparent() {
        String traceparent = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/aggregate").header(TraceContext.TRACEPARENT_HEADER, traceparent));
        ProblemDetail detail = ProblemDetail.forStatus(400);

        ProblemResponseSupport.enrich(detail, exchange);

        assertThat(detail.getProperties()).containsEntry("traceId", "0af7651916cd43dd8448eb211c80319c");
        assertThat(detail.getProperties()).containsKey("timestamp");
        assertThat(detail.getInstance()).isEqualTo(URI.create("/requests/0af7651916cd43dd8448eb211c80319c"));
        assertThat(exchange.getResponse().getHeaders().getFirst(TraceContext.TRACEPARENT_HEADER))
                .isNull();
    }

    @Test
    void enrich_generatesTraceIdWhenNoValidTraceparentExists() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/aggregate").header(TraceContext.TRACEPARENT_HEADER, "not-valid"));
        exchange.getResponse().getHeaders().set(ForwardedHeaders.REQUEST_ID_HEADER, "req/unsafe id");
        ProblemDetail detail = ProblemDetail.forStatus(500);

        ProblemResponseSupport.enrich(detail, exchange);

        String responseTraceparent = exchange.getResponse().getHeaders().getFirst(TraceContext.TRACEPARENT_HEADER);
        String generatedTraceId = TraceContext.traceIdFromTraceparent(responseTraceparent);
        assertThat(generatedTraceId).isNotNull();
        assertThat(detail.getProperties()).containsEntry("traceId", generatedTraceId);
        assertThat(detail.getInstance()).isEqualTo(URI.create("/requests/" + generatedTraceId));
    }

    @Test
    void responseHeaders_copiesExistingHeadersAndPrefersResponseTraceparent() {
        String responseTraceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/aggregate").header("traceparent", "broken"));
        exchange.getResponse().getHeaders().set(TraceContext.TRACEPARENT_HEADER, responseTraceparent);
        HttpHeaders inputHeaders = new HttpHeaders();
        inputHeaders.set("X-Test", "value");

        HttpHeaders headers = ProblemResponseSupport.responseHeaders(inputHeaders, exchange);

        assertThat(headers.getFirst("X-Test")).isEqualTo("value");
        assertThat(headers.getFirst(TraceContext.TRACEPARENT_HEADER)).isEqualTo(responseTraceparent);
    }

    @Test
    void responseHeaders_generatesTraceparentWhenNoValidTraceContextExists() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/aggregate"));

        HttpHeaders headers = ProblemResponseSupport.responseHeaders(null, exchange);

        assertThat(TraceContext.traceIdFromTraceparent(headers.getFirst(TraceContext.TRACEPARENT_HEADER)))
                .isNotNull();
        assertThat(headers.getFirst(TraceContext.TRACEPARENT_HEADER))
                .isEqualTo(exchange.getResponse().getHeaders().getFirst(TraceContext.TRACEPARENT_HEADER));
    }

    @Test
    void requestId_prefersResponseHeaderThenRequestHeaderAndCanBeAbsent() throws Exception {
        Method requestIdMethod = ProblemResponseSupport.class.getDeclaredMethod(
                "requestId", org.springframework.web.server.ServerWebExchange.class);
        requestIdMethod.setAccessible(true);

        MockServerWebExchange responseHeaderExchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/aggregate").header(ForwardedHeaders.REQUEST_ID_HEADER, "request-id"));
        responseHeaderExchange.getResponse().getHeaders().set(ForwardedHeaders.REQUEST_ID_HEADER, "response-id");
        MockServerWebExchange requestHeaderExchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/aggregate").header(ForwardedHeaders.REQUEST_ID_HEADER, "request-id"));
        MockServerWebExchange missingHeaderExchange =
                MockServerWebExchange.from(MockServerHttpRequest.get("/aggregate"));

        assertThat(requestIdMethod.invoke(null, responseHeaderExchange)).isEqualTo("response-id");
        assertThat(requestIdMethod.invoke(null, requestHeaderExchange)).isEqualTo("request-id");
        assertThat(requestIdMethod.invoke(null, missingHeaderExchange)).isNull();
    }
}
