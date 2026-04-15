package com.example.aggregation.downstream;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

class WebClientDownstreamClientTest {

    private static final ObjectNode REQUEST = JsonNodeFactory.instance.objectNode().put("id", "request-1");
    private static final DownstreamRequest DOWNSTREAM_REQUEST = new DownstreamRequest(
        DownstreamHeaders.builder()
            .authorization("Bearer token")
            .requestId("req-1")
            .correlationId("corr-1")
            .acceptLanguage("en-US")
            .build(),
        true
    );

    @ParameterizedTest
    @MethodSource("clients")
    void post_sendsExpectedRequest(DownstreamClientCase clientCase) {
        AtomicReference<ClientRequest> capturedRequest = new AtomicReference<>();
        WebClient webClient = webClient(capturedRequest, ClientResponse.create(HttpStatus.OK)
            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .body("{\"status\":\"ok\"}")
            .build());

        StepVerifier.create(clientCase.invocationFactory().apply(webClient).post(REQUEST, DOWNSTREAM_REQUEST))
            .assertNext(response -> assertThat(response.path("status").asString()).isEqualTo("ok"))
            .verifyComplete();

        ClientRequest request = capturedRequest.get();
        assertThat(request.method().name()).isEqualTo("POST");
        assertThat(request.url().getPath()).isEqualTo(clientCase.path());
        assertThat(request.url().getQuery()).isEqualTo("detokenize=true");
        assertThat(request.headers().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(request.headers().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer token");
        assertThat(request.headers().getFirst("X-Request-Id")).isEqualTo("req-1");
        assertThat(request.headers().getFirst("X-Correlation-Id")).isEqualTo("corr-1");
        assertThat(request.headers().getFirst(HttpHeaders.ACCEPT_LANGUAGE)).isEqualTo("en-US");
    }

    @ParameterizedTest
    @MethodSource("clients")
    void post_mapsErrorStatusToIllegalStateException(DownstreamClientCase clientCase) {
        WebClient webClient = webClient(new AtomicReference<>(), ClientResponse.create(HttpStatus.BAD_GATEWAY)
            .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_PLAIN_VALUE)
            .body("downstream unavailable")
            .build());

        StepVerifier.create(clientCase.invocationFactory().apply(webClient).post(REQUEST, DOWNSTREAM_REQUEST))
            .expectErrorSatisfies(error -> assertThat(error)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(clientCase.errorPrefix() + " downstream failed: downstream unavailable"))
            .verify();
    }

    @Test
    void post_usesDefaultErrorMessageForEmptyErrorBody() {
        WebClient webClient = webClient(new AtomicReference<>(), ClientResponse.create(HttpStatus.BAD_GATEWAY).build());

        StepVerifier.create(new WebClientMainClient(webClient).postMain(REQUEST, DOWNSTREAM_REQUEST))
            .expectErrorSatisfies(error -> assertThat(error)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Main downstream failed: main downstream request failed"))
            .verify();
    }

    private static Stream<DownstreamClientCase> clients() {
        return Stream.of(
            new DownstreamClientCase("/main", "Main", webClient -> new WebClientMainClient(webClient)::postMain),
            new DownstreamClientCase("/owners", "Owners", webClient -> new WebClientOwnersClient(webClient)::postOwners),
            new DownstreamClientCase("/pricing", "Pricing", webClient -> new WebClientPricingClient(webClient)::postPricing),
            new DownstreamClientCase("/profiles", "Profile", webClient -> new WebClientProfileClient(webClient)::postProfile)
        );
    }

    private static WebClient webClient(AtomicReference<ClientRequest> capturedRequest, ClientResponse response) {
        return WebClient.builder()
            .baseUrl("https://downstream.example")
            .exchangeFunction(request -> {
                capturedRequest.set(request);
                return Mono.just(response);
            })
            .build();
    }

    private record DownstreamClientCase(
        String path,
        String errorPrefix,
        Function<WebClient, DownstreamClientInvocation> invocationFactory
    ) {
    }

    @FunctionalInterface
    private interface DownstreamClientInvocation {
        Mono<JsonNode> post(ObjectNode request, DownstreamRequest downstreamRequest);
    }
}
