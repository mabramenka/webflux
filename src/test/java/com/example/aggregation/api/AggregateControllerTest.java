package com.example.aggregation.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.aggregation.application.AggregateService;
import com.example.aggregation.downstream.DownstreamRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@WebFluxTest(controllers = AggregateController.class)
class AggregateControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private AggregateService aggregateService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void aggregate_acceptsPostBodyAndHeaders_andReturnsJson() {
        ObjectNode mergedResponse = objectMapper.createObjectNode();
        mergedResponse.put("status", "ok");

        when(aggregateService.aggregate(any(ObjectNode.class), any(DownstreamRequest.class)))
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
        ArgumentCaptor<DownstreamRequest> downstreamRequestCaptor = ArgumentCaptor.forClass(DownstreamRequest.class);
        verify(aggregateService).aggregate(requestCaptor.capture(), downstreamRequestCaptor.capture());

        org.assertj.core.api.Assertions.assertThat(requestCaptor.getValue().path("customerId").asString()).isEqualTo("cust-1");
        DownstreamRequest downstreamRequest = downstreamRequestCaptor.getValue();
        org.assertj.core.api.Assertions.assertThat(downstreamRequest.headers().authorization()).isEqualTo("Bearer abc");
        org.assertj.core.api.Assertions.assertThat(downstreamRequest.headers().requestId()).isEqualTo("req-123");
        org.assertj.core.api.Assertions.assertThat(downstreamRequest.headers().correlationId()).isEqualTo("corr-456");
        org.assertj.core.api.Assertions.assertThat(downstreamRequest.headers().acceptLanguage()).isEqualTo("en-US");
        org.assertj.core.api.Assertions.assertThat(downstreamRequest.detokenize()).isTrue();
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
}
