package dev.abramenka.aggregation.api;

import static dev.abramenka.aggregation.model.ForwardedHeaders.CORRELATION_ID_HEADER;
import static dev.abramenka.aggregation.model.ForwardedHeaders.REQUEST_ID_HEADER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.abramenka.aggregation.config.ClientRequestContextFactory;
import dev.abramenka.aggregation.config.ServerClientRequestContextArgumentResolver;
import dev.abramenka.aggregation.config.WebFluxConfig;
import dev.abramenka.aggregation.error.InvalidAggregationRequestException;
import dev.abramenka.aggregation.model.ClientRequestContext;
import dev.abramenka.aggregation.service.AggregateService;
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
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@WebFluxTest(controllers = AggregateController.class)
@Import({
    ClientRequestContextFactory.class,
    GlobalExceptionHandler.class,
    ServerClientRequestContextArgumentResolver.class,
    WebFluxConfig.class
})
class AggregateControllerTest {

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

        when(aggregateService.aggregate(any(ObjectNode.class), any(ClientRequestContext.class)))
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
                .bodyValue("""
                {"ids":["id-x19"],"include":["account","owners"]}
                """)
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.status")
                .isEqualTo("ok");

        ArgumentCaptor<ObjectNode> requestCaptor = ArgumentCaptor.forClass(ObjectNode.class);
        ArgumentCaptor<ClientRequestContext> clientRequestContextCaptor =
                ArgumentCaptor.forClass(ClientRequestContext.class);
        verify(aggregateService).aggregate(requestCaptor.capture(), clientRequestContextCaptor.capture());

        assertThat(requestCaptor.getValue().path("ids").get(0).asString()).isEqualTo("id-x19");
        assertThat(requestCaptor.getValue().path("include").get(0).asString()).isEqualTo("account");
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
                .bodyValue("""
                {"ids":["id-x19"]}
                """)
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.type")
                .isEqualTo("/problems/invalid-aggregation-request")
                .jsonPath("$.detail")
                .isEqualTo("'detokenize' must be either true or false");
    }

    @Test
    void aggregate_rejectsBlankDetokenizeQueryParam() {
        webTestClient
                .post()
                .uri("/api/v1/aggregate?detokenize=")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                {"ids":["id-x19"]}
                """)
                .exchange()
                .expectStatus()
                .isBadRequest();
    }

    @Test
    void aggregate_returnsProblemDetailWhenServiceRejectsRequest() {
        when(aggregateService.aggregate(any(ObjectNode.class), any(ClientRequestContext.class)))
                .thenReturn(
                        Mono.error(new InvalidAggregationRequestException("Unknown aggregation enrichment(s): foo")));

        webTestClient
                .post()
                .uri("/api/v1/aggregate")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                {"ids":["id-x19"]}
                """)
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody(String.class)
                .value(body -> assertThat(body)
                        .contains("\"status\":" + HttpStatus.BAD_REQUEST.value())
                        .contains("Unknown aggregation enrichment(s): foo")
                        .contains("/problems/invalid-aggregation-request")
                        .contains("/api/v1/aggregate"));
    }

    @Test
    void aggregate_returnsInternalProblemDetailWhenServiceFailsInternally() {
        when(aggregateService.aggregate(any(ObjectNode.class), any(ClientRequestContext.class)))
                .thenReturn(Mono.error(new IllegalStateException("Duplicate aggregation enrichment name: account")));

        webTestClient
                .post()
                .uri("/api/v1/aggregate")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                {"ids":["id-x19"]}
                """)
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR)
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody(String.class)
                .value(body -> assertThat(body)
                        .contains("\"status\":" + HttpStatus.INTERNAL_SERVER_ERROR.value())
                        .contains("Internal aggregation error")
                        .contains("/problems/internal-aggregation-error")
                        .contains("/api/v1/aggregate"));
    }
}
