package com.example.aggregation.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.aggregation.service.AggregateService;
import com.example.aggregation.web.DownstreamHeaders;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@WebFluxTest(controllers = AggregateController.class)
class AggregateControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockBean
    private AggregateService aggregateService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void aggregate_acceptsPostBodyAndHeaders_andReturnsJson() {
        ObjectNode mergedResponse = objectMapper.createObjectNode();
        mergedResponse.put("status", "ok");

        when(aggregateService.aggregate(any(ObjectNode.class), any(DownstreamHeaders.class)))
            .thenReturn(Mono.just(mergedResponse));

        webTestClient.post()
            .uri("/api/v1/aggregate")
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
        ArgumentCaptor<DownstreamHeaders> headersCaptor = ArgumentCaptor.forClass(DownstreamHeaders.class);
        verify(aggregateService).aggregate(requestCaptor.capture(), headersCaptor.capture());

        org.assertj.core.api.Assertions.assertThat(requestCaptor.getValue().path("customerId").asText()).isEqualTo("cust-1");
        org.assertj.core.api.Assertions.assertThat(headersCaptor.getValue().authorization()).isEqualTo("Bearer abc");
        org.assertj.core.api.Assertions.assertThat(headersCaptor.getValue().requestId()).isEqualTo("req-123");
        org.assertj.core.api.Assertions.assertThat(headersCaptor.getValue().correlationId()).isEqualTo("corr-456");
        org.assertj.core.api.Assertions.assertThat(headersCaptor.getValue().acceptLanguage()).isEqualTo("en-US");
    }
}
