package dev.abramenka.aggregation.api;

import static dev.abramenka.aggregation.model.ForwardedHeaders.CORRELATION_ID_HEADER;
import static dev.abramenka.aggregation.model.ForwardedHeaders.REQUEST_ID_HEADER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.abramenka.aggregation.model.ClientRequestContext;
import dev.abramenka.aggregation.model.TraceContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import reactor.core.publisher.Mono;
import tools.jackson.databind.node.ObjectNode;

class AggregateControllerRequestValidationTest extends AggregateControllerWebFluxTestSupport {

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
                .isEqualTo("/problems/invalid-request-body")
                .jsonPath("$.errorCode")
                .isEqualTo("CLIENT-INVALID-BODY")
                .jsonPath("$.detail")
                .isEqualTo("The request body could not be parsed or does not match the expected format.");
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
}
