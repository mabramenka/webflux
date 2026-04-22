package dev.abramenka.aggregation.api;

import static dev.abramenka.aggregation.model.ForwardedHeaders.CORRELATION_ID_HEADER;
import static dev.abramenka.aggregation.model.ForwardedHeaders.REQUEST_ID_HEADER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.abramenka.aggregation.config.ClientRequestContextFactory;
import dev.abramenka.aggregation.config.MdcPropagationConfig;
import dev.abramenka.aggregation.config.RequestContextMdcFilter;
import dev.abramenka.aggregation.config.ServerClientRequestContextArgumentResolver;
import dev.abramenka.aggregation.config.WebFluxConfig;
import dev.abramenka.aggregation.error.AggregationErrorResponseAdvice;
import dev.abramenka.aggregation.error.DownstreamClientException;
import dev.abramenka.aggregation.error.UnsupportedAggregationPartException;
import dev.abramenka.aggregation.model.ClientRequestContext;
import dev.abramenka.aggregation.model.TraceContext;
import dev.abramenka.aggregation.service.AggregateService;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.ErrorResponseException;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@WebFluxTest(controllers = AggregateController.class)
@Import({
    AggregationErrorResponseAdvice.class,
    ClientRequestContextFactory.class,
    MdcPropagationConfig.class,
    RequestContextMdcFilter.class,
    ServerClientRequestContextArgumentResolver.class,
    WebFluxConfig.class
})
class AggregateControllerTest {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String TRACEPARENT = "00-" + TRACE_ID + "-00f067aa0ba902b7-00";

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private AggregateService aggregateService;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void aggregate_acceptsPostBodyAndHeaders_andReturnsJson() {
        ObjectNode mergedResponse = objectMapper.createObjectNode();
        mergedResponse.put("status", "ok");

        when(aggregateService.aggregate(any(AggregateRequest.class), any(ClientRequestContext.class)))
                .thenReturn(Mono.just(mergedResponse));

        webTestClient
                .post()
                .uri("/api/v1/aggregate?detokenize=true")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer abc")
                .header(REQUEST_ID_HEADER, "req-123")
                .header(CORRELATION_ID_HEADER, "corr-456")
                .header(HttpHeaders.ACCEPT_LANGUAGE, "en-US")
                .header(TraceContext.TRACEPARENT_HEADER, TRACEPARENT)
                .bodyValue("""
                {"ids":["AB123456789"],"include":["account","owners"]}
                """)
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectHeader()
                .valueEquals(TraceContext.TRACEPARENT_HEADER, TRACEPARENT)
                .expectBody()
                .jsonPath("$.status")
                .isEqualTo("ok");

        ArgumentCaptor<AggregateRequest> requestCaptor = ArgumentCaptor.forClass(AggregateRequest.class);
        ArgumentCaptor<ClientRequestContext> clientRequestContextCaptor =
                ArgumentCaptor.forClass(ClientRequestContext.class);
        verify(aggregateService).aggregate(requestCaptor.capture(), clientRequestContextCaptor.capture());

        assertThat(requestCaptor.getValue().ids()).containsExactly("AB123456789");
        assertThat(requestCaptor.getValue().include()).containsExactly("account", "owners");
        ClientRequestContext clientRequestContext = clientRequestContextCaptor.getValue();
        assertThat(clientRequestContext.headers().authorization()).isEqualTo("Bearer abc");
        assertThat(clientRequestContext.headers().requestId()).isEqualTo("req-123");
        assertThat(clientRequestContext.headers().correlationId()).isEqualTo("corr-456");
        assertThat(clientRequestContext.headers().acceptLanguage()).isEqualTo("en-US");
        assertThat(clientRequestContext.detokenize()).isTrue();
    }

    @Test
    void aggregate_rejectsInvalidDetokenizeQueryParam() {
        webTestClient
                .post()
                .uri("/api/v1/aggregate?detokenize=yes")
                .contentType(MediaType.APPLICATION_JSON)
                .header(REQUEST_ID_HEADER, "req-789")
                .header(CORRELATION_ID_HEADER, "corr-789")
                .header(TraceContext.TRACEPARENT_HEADER, TRACEPARENT)
                .bodyValue("""
                {"ids":["AB123456789"]}
                """)
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectHeader()
                .valueEquals(TraceContext.TRACEPARENT_HEADER, TRACEPARENT)
                .expectBody()
                .jsonPath("$.type")
                .isEqualTo("/problems/validation")
                .jsonPath("$.traceId")
                .isEqualTo(TRACE_ID)
                .jsonPath("$.errorCode")
                .isEqualTo("CLIENT-VALIDATION")
                .jsonPath("$.category")
                .isEqualTo("CLIENT_REQUEST")
                .jsonPath("$.retryable")
                .isEqualTo(false)
                .jsonPath("$.detail")
                .isEqualTo("One or more request fields failed validation.")
                .jsonPath("$.violations[0].pointer")
                .isEqualTo("/query/detokenize")
                .jsonPath("$.violations[0].message")
                .isEqualTo("'detokenize' must be either true or false");
    }

    @Test
    void aggregate_problemDetailIncludesGeneratedTraceIdWhenHeaderMissing() {
        webTestClient
                .post()
                .uri("/api/v1/aggregate")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectHeader()
                .exists(REQUEST_ID_HEADER)
                .expectHeader()
                .exists(TraceContext.TRACEPARENT_HEADER)
                .expectBody()
                .jsonPath("$.traceId")
                .value(traceId -> assertThat((String) traceId).matches("[0-9a-f]{32}"))
                .jsonPath("$.type")
                .isEqualTo("/problems/validation")
                .jsonPath("$.timestamp")
                .exists()
                .jsonPath("$.requestId")
                .doesNotExist();
    }

    @Test
    void aggregate_generatesTraceparentWhenInboundHeaderIsInvalid() {
        String invalidTraceparent = "00-4BF92F3577B34DA6A3CE929D0E0E4736-00f067aa0ba902b7-01";

        webTestClient
                .post()
                .uri("/api/v1/aggregate")
                .contentType(MediaType.APPLICATION_JSON)
                .header(TraceContext.TRACEPARENT_HEADER, invalidTraceparent)
                .bodyValue("{}")
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectHeader()
                .value(TraceContext.TRACEPARENT_HEADER, value -> assertThat(value)
                        .matches("00-[0-9a-f]{32}-[0-9a-f]{16}-01")
                        .isNotEqualTo(invalidTraceparent))
                .expectBody()
                .jsonPath("$.traceId")
                .value(traceId -> assertThat((String) traceId).matches("[0-9a-f]{32}"));
    }

    @Test
    void aggregate_rejectsBlankDetokenizeQueryParam() {
        webTestClient
                .post()
                .uri("/api/v1/aggregate?detokenize=")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                {"ids":["AB123456789"]}
                """)
                .exchange()
                .expectStatus()
                .isBadRequest();
    }

    @Test
    void aggregate_rejectsMalformedJson() {
        webTestClient
                .post()
                .uri("/api/v1/aggregate")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{")
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.type")
                .isEqualTo("/problems/validation")
                .jsonPath("$.errorCode")
                .isEqualTo("CLIENT-VALIDATION")
                .jsonPath("$.detail")
                .isEqualTo("One or more request fields failed validation.");
    }

    @Test
    void aggregate_rejectsMissingIds() {
        webTestClient
                .post()
                .uri("/api/v1/aggregate")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.type")
                .isEqualTo("/problems/validation")
                .jsonPath("$.detail")
                .isEqualTo("One or more request fields failed validation.")
                .jsonPath("$.violations[0].pointer")
                .isEqualTo("/ids");
    }

    @Test
    void aggregate_rejectsEmptyIds() {
        webTestClient
                .post()
                .uri("/api/v1/aggregate")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                {"ids":[]}
                """)
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .jsonPath("$.type")
                .isEqualTo("/problems/validation");
    }

    @Test
    void aggregate_rejectsBlankId() {
        webTestClient
                .post()
                .uri("/api/v1/aggregate")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                {"ids":["  "]}
                """)
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .jsonPath("$.type")
                .isEqualTo("/problems/validation")
                .jsonPath("$.violations[0].pointer")
                .isEqualTo("/ids/0");
    }

    @Test
    void aggregate_rejectsNullId() {
        webTestClient
                .post()
                .uri("/api/v1/aggregate")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                {"ids":[null]}
                """)
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .jsonPath("$.type")
                .isEqualTo("/problems/validation");
    }

    @Test
    void aggregate_returnsProblemDetailWhenServiceRejectsRequest() {
        when(aggregateService.aggregate(any(AggregateRequest.class), any(ClientRequestContext.class)))
                .thenReturn(Mono.error(new UnsupportedAggregationPartException(List.of("foo"))));

        webTestClient
                .post()
                .uri("/api/v1/aggregate")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                {"ids":["AB123456789"]}
                """)
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.type")
                .isEqualTo("/problems/validation")
                .jsonPath("$.status")
                .isEqualTo(HttpStatus.BAD_REQUEST.value())
                .jsonPath("$.errorCode")
                .isEqualTo("CLIENT-VALIDATION")
                .jsonPath("$.detail")
                .isEqualTo("One or more request fields failed validation.")
                .jsonPath("$.violations[0].pointer")
                .isEqualTo("/request/include")
                .jsonPath("$.instance")
                .value(instance -> assertThat((String) instance).startsWith("/requests/"));
    }

    @Test
    void aggregate_returnsInternalProblemDetailWhenUnexpectedExceptionEscapes() {
        when(aggregateService.aggregate(any(AggregateRequest.class), any(ClientRequestContext.class)))
                .thenReturn(Mono.error(new IllegalStateException("boom")));

        webTestClient
                .post()
                .uri("/api/v1/aggregate")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                {"ids":["AB123456789"]}
                """)
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR)
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.type")
                .isEqualTo("/problems/platform/internal")
                .jsonPath("$.errorCode")
                .isEqualTo("PLATFORM-INTERNAL")
                .jsonPath("$.category")
                .isEqualTo("PLATFORM")
                .jsonPath("$.detail")
                .isEqualTo("The service encountered an unexpected internal error.");
    }

    @Test
    void aggregate_returnsInternalProblemDetailWhenThrowableEscapes() {
        when(aggregateService.aggregate(any(AggregateRequest.class), any(ClientRequestContext.class)))
                .thenReturn(Mono.error(new LinkageError("linkage failure")));

        webTestClient
                .post()
                .uri("/api/v1/aggregate")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                {"ids":["AB123456789"]}
                """)
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR)
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.type")
                .isEqualTo("/problems/platform/internal")
                .jsonPath("$.errorCode")
                .isEqualTo("PLATFORM-INTERNAL")
                .jsonPath("$.detail")
                .isEqualTo("The service encountered an unexpected internal error.");
    }

    @Test
    void aggregate_returnsProblemDetailWhenDownstreamFails() {
        when(aggregateService.aggregate(any(AggregateRequest.class), any(ClientRequestContext.class)))
                .thenReturn(Mono.error(DownstreamClientException.upstreamStatus("Account", HttpStatus.BAD_REQUEST)));

        webTestClient
                .post()
                .uri("/api/v1/aggregate")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                {"ids":["AB123456789"]}
                """)
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.BAD_GATEWAY)
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.type")
                .isEqualTo("/problems/enrichment/bad-response")
                .jsonPath("$.detail")
                .isEqualTo("A required enrichment dependency returned an unexpected response status.")
                .jsonPath("$.errorCode")
                .isEqualTo("ENRICH-BAD-RESPONSE")
                .jsonPath("$.dependency")
                .isEqualTo("enricher:account")
                .jsonPath("$.downstreamStatus")
                .doesNotExist();
    }

    @Test
    void aggregateOne_returnsJsonForSingleId() {
        ObjectNode mergedResponse = objectMapper.createObjectNode();
        mergedResponse.put("status", "ok");

        when(aggregateService.aggregate(any(AggregateRequest.class), any(ClientRequestContext.class)))
                .thenReturn(Mono.just(mergedResponse));

        webTestClient
                .get()
                .uri("/api/v1/aggregate/ab123456789?include=account&include=owners")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.status")
                .isEqualTo("ok");

        ArgumentCaptor<AggregateRequest> requestCaptor = ArgumentCaptor.forClass(AggregateRequest.class);
        verify(aggregateService).aggregate(requestCaptor.capture(), any(ClientRequestContext.class));
        assertThat(requestCaptor.getValue().ids()).containsExactly("ab123456789");
        assertThat(requestCaptor.getValue().include()).containsExactly("account", "owners");
    }

    @Test
    void aggregateOne_rejectsIdThatDoesNotMatchPattern() {
        webTestClient
                .get()
                .uri("/api/v1/aggregate/not-an-id")
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .jsonPath("$.type")
                .isEqualTo("/problems/validation")
                .jsonPath("$.violations[0].pointer")
                .isEqualTo("/path/id")
                .jsonPath("$.violations[0].message")
                .exists();
    }

    @Test
    void aggregate_rejectsUnsupportedApiVersion() {
        webTestClient
                .get()
                .uri("/api/v99/aggregate/AB123456789")
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
    }

    @Test
    void aggregate_rejectsMethodNotAllowed() {
        webTestClient
                .get()
                .uri("/api/v1/aggregate")
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.METHOD_NOT_ALLOWED)
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.type")
                .isEqualTo("/problems/method-not-allowed")
                .jsonPath("$.errorCode")
                .isEqualTo("CLIENT-METHOD-NOT-ALLOWED")
                .jsonPath("$.status")
                .isEqualTo(HttpStatus.METHOD_NOT_ALLOWED.value());
    }

    @Test
    void aggregate_rejectsUnsupportedAcceptHeader() {
        webTestClient
                .post()
                .uri("/api/v1/aggregate")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_XML)
                .bodyValue("""
                {"ids":["AB123456789"]}
                """)
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.NOT_ACCEPTABLE)
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.type")
                .isEqualTo("/problems/not-acceptable")
                .jsonPath("$.errorCode")
                .isEqualTo("CLIENT-NOT-ACCEPTABLE")
                .jsonPath("$.status")
                .isEqualTo(HttpStatus.NOT_ACCEPTABLE.value());
    }

    @Test
    void aggregate_rejectsUnsupportedContentType() {
        webTestClient
                .post()
                .uri("/api/v1/aggregate")
                .contentType(MediaType.TEXT_PLAIN)
                .bodyValue("ids=AB123456789")
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.type")
                .isEqualTo("/problems/unsupported-media")
                .jsonPath("$.errorCode")
                .isEqualTo("CLIENT-UNSUPPORTED-MEDIA")
                .jsonPath("$.status")
                .isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE.value());
    }

    @Test
    void aggregate_returnsProblemDetailWhenResourceIsNotFound() {
        webTestClient
                .get()
                .uri("/api/v1/does-not-exist")
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.NOT_FOUND)
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
    }

    @Test
    void aggregate_mapsFrameworkUnauthorizedToCatalogEntry() {
        when(aggregateService.aggregate(any(AggregateRequest.class), any(ClientRequestContext.class)))
                .thenReturn(Mono.error(new ErrorResponseException(HttpStatus.UNAUTHORIZED)));

        webTestClient
                .post()
                .uri("/api/v1/aggregate")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                {"ids":["AB123456789"]}
                """)
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectBody()
                .jsonPath("$.type")
                .isEqualTo("/problems/unauthenticated")
                .jsonPath("$.errorCode")
                .isEqualTo("CLIENT-UNAUTHENTICATED");
    }

    @Test
    void aggregate_mapsFrameworkForbiddenToCatalogEntry() {
        when(aggregateService.aggregate(any(AggregateRequest.class), any(ClientRequestContext.class)))
                .thenReturn(Mono.error(new ErrorResponseException(HttpStatus.FORBIDDEN)));

        webTestClient
                .post()
                .uri("/api/v1/aggregate")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                {"ids":["AB123456789"]}
                """)
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody()
                .jsonPath("$.type")
                .isEqualTo("/problems/forbidden")
                .jsonPath("$.errorCode")
                .isEqualTo("CLIENT-FORBIDDEN");
    }

    @Test
    void aggregate_mapsFrameworkRateLimitToCatalogEntry() {
        when(aggregateService.aggregate(any(AggregateRequest.class), any(ClientRequestContext.class)))
                .thenReturn(Mono.error(new ErrorResponseException(HttpStatus.TOO_MANY_REQUESTS)));

        webTestClient
                .post()
                .uri("/api/v1/aggregate")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                {"ids":["AB123456789"]}
                """)
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
                .expectBody()
                .jsonPath("$.type")
                .isEqualTo("/problems/rate-limited")
                .jsonPath("$.errorCode")
                .isEqualTo("CLIENT-RATE-LIMITED")
                .jsonPath("$.retryable")
                .isEqualTo(true);
    }

    @Test
    void aggregate_mapsRejectedExecutionToOverloadedProblem() {
        when(aggregateService.aggregate(any(AggregateRequest.class), any(ClientRequestContext.class)))
                .thenReturn(Mono.error(new RejectedExecutionException("pool exhausted")));

        webTestClient
                .post()
                .uri("/api/v1/aggregate")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                {"ids":["AB123456789"]}
                """)
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectHeader()
                .valueEquals(HttpHeaders.RETRY_AFTER, "1")
                .expectBody()
                .jsonPath("$.type")
                .isEqualTo("/problems/platform/overloaded")
                .jsonPath("$.errorCode")
                .isEqualTo("PLATFORM-OVERLOADED")
                .jsonPath("$.retryable")
                .isEqualTo(true);
    }

    @Test
    void aggregate_mapsUnknownFrameworkStatusToPlatformInternal() {
        when(aggregateService.aggregate(any(AggregateRequest.class), any(ClientRequestContext.class)))
                .thenReturn(Mono.error(new ErrorResponseException(HttpStatus.CONFLICT)));

        webTestClient
                .post()
                .uri("/api/v1/aggregate")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                {"ids":["AB123456789"]}
                """)
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR)
                .expectBody()
                .jsonPath("$.type")
                .isEqualTo("/problems/platform/internal")
                .jsonPath("$.errorCode")
                .isEqualTo("PLATFORM-INTERNAL");
    }
}
