package com.example.aggregation.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.aggregation.service.AggregateService;
import com.example.aggregation.client.ClientRequestContext;
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
@Import(GlobalExceptionHandler.class)
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

        webTestClient.post()
            .uri("/api/v1/aggregate?detokenize=true")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, "Bearer abc")
            .header("X-Request-Id", "req-123")
            .header("X-Correlation-Id", "corr-456")
            .header(HttpHeaders.ACCEPT_LANGUAGE, "en-US")
            .bodyValue("""
                {"customerId":"cust-1","market":"US"}
                """)
            .exchange()
            .expectStatus().isOk()
            .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
            .expectBody()
            .jsonPath("$.status").isEqualTo("ok");

        ArgumentCaptor<ObjectNode> requestCaptor = ArgumentCaptor.forClass(ObjectNode.class);
        ArgumentCaptor<ClientRequestContext> clientRequestContextCaptor = ArgumentCaptor.forClass(ClientRequestContext.class);
        verify(aggregateService).aggregate(requestCaptor.capture(), clientRequestContextCaptor.capture());

        org.assertj.core.api.Assertions.assertThat(requestCaptor.getValue().path("customerId").asString()).isEqualTo("cust-1");
        ClientRequestContext clientRequestContext = clientRequestContextCaptor.getValue();
        org.assertj.core.api.Assertions.assertThat(clientRequestContext.headers().authorization()).isEqualTo("Bearer abc");
        org.assertj.core.api.Assertions.assertThat(clientRequestContext.headers().requestId()).isEqualTo("req-123");
        org.assertj.core.api.Assertions.assertThat(clientRequestContext.headers().correlationId()).isEqualTo("corr-456");
        org.assertj.core.api.Assertions.assertThat(clientRequestContext.headers().acceptLanguage()).isEqualTo("en-US");
        org.assertj.core.api.Assertions.assertThat(clientRequestContext.detokenize()).isTrue();
    }

    @Test
    void aggregate_rejectsInvalidDetokenizeQueryParam() {
        webTestClient.post()
            .uri("/api/v1/aggregate?detokenize=yes")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""
                {"customerId":"cust-1"}
                """)
            .exchange()
            .expectStatus().isBadRequest();
    }

    @Test
    void aggregate_returnsProblemDetailWhenServiceRejectsRequest() {
        when(aggregateService.aggregate(any(ObjectNode.class), any(ClientRequestContext.class)))
            .thenReturn(Mono.error(new IllegalArgumentException("Unknown aggregation enrichment(s): foo")));

        webTestClient.post()
            .uri("/api/v1/aggregate")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""
                {"customerId":"cust-1"}
                """)
            .exchange()
            .expectStatus().isBadRequest()
            .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .expectBody()
            .jsonPath("$.status").isEqualTo(HttpStatus.BAD_REQUEST.value())
            .jsonPath("$.detail").isEqualTo("Unknown aggregation enrichment(s): foo");
    }
}
