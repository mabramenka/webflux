package dev.abramenka.aggregation.client;

import static dev.abramenka.aggregation.model.ForwardedHeaders.CORRELATION_ID_HEADER;
import static dev.abramenka.aggregation.model.ForwardedHeaders.REQUEST_ID_HEADER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.abramenka.aggregation.error.DownstreamClientException;
import dev.abramenka.aggregation.model.ClientRequestContext;
import dev.abramenka.aggregation.model.ForwardedHeaders;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.support.WebClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

class HttpServiceExternalClientsTest {

    private static final ObjectNode REQUEST =
            JsonNodeFactory.instance.objectNode().put("id", "request-1");
    private static final ClientRequestContext CLIENT_REQUEST_CONTEXT = new ClientRequestContext(
            ForwardedHeaders.builder()
                    .authorization("Bearer token")
                    .requestId("req-1")
                    .correlationId("corr-1")
                    .acceptLanguage("en-US")
                    .build(),
            true);

    @ParameterizedTest
    @MethodSource("clients")
    void fetch_sendsExpectedRequest(WebClientCase clientCase) {
        AtomicReference<ClientRequest> capturedRequest = new AtomicReference<>();
        WebClient webClient = webClient(
                capturedRequest,
                ClientResponse.create(HttpStatus.OK)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .body("{\"status\":\"ok\"}")
                        .build());

        StepVerifier.create(clientCase.invocationFactory().apply(webClient).fetch(REQUEST, CLIENT_REQUEST_CONTEXT))
                .assertNext(response ->
                        assertThat(response.path("status").asString()).isEqualTo("ok"))
                .verifyComplete();

        ClientRequest request = capturedRequest.get();
        assertThat(request.method().name()).isEqualTo("POST");
        assertThat(request.url().getPath()).isEqualTo(clientCase.path());
        assertThat(request.url().getQuery()).isEqualTo("detokenize=true");
        assertThat(request.headers().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(request.headers().getAccept()).containsExactly(MediaType.APPLICATION_JSON);
        assertThat(request.headers().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer token");
        assertThat(request.headers().getFirst(REQUEST_ID_HEADER)).isEqualTo("req-1");
        assertThat(request.headers().getFirst(CORRELATION_ID_HEADER)).isEqualTo("corr-1");
        assertThat(request.headers().getFirst(HttpHeaders.ACCEPT_LANGUAGE)).isEqualTo("en-US");
    }

    @ParameterizedTest
    @MethodSource("clients")
    void fetch_mapsErrorStatusToIllegalStateException(WebClientCase clientCase) {
        WebClient webClient = webClient(
                new AtomicReference<>(),
                ClientResponse.create(HttpStatus.BAD_REQUEST)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_PLAIN_VALUE)
                        .body("client unavailable")
                        .build());

        StepVerifier.create(clientCase.invocationFactory().apply(webClient).fetch(REQUEST, CLIENT_REQUEST_CONTEXT))
                .expectErrorSatisfies(error -> {
                    assertThat(error)
                            .isInstanceOf(DownstreamClientException.class)
                            .hasMessage(clientCase.errorPrefix() + " client failed: client unavailable");
                    DownstreamClientException clientException = (DownstreamClientException) error;
                    assertThat(clientException.clientName()).isEqualTo(clientCase.errorPrefix());
                    assertThat(clientException.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
                    assertThat(clientException.downstreamStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(clientException.responseBody()).isEqualTo("client unavailable");
                })
                .verify();
    }

    @ParameterizedTest
    @MethodSource("clients")
    void fetch_usesDefaultErrorMessageForEmptyErrorBody(WebClientCase clientCase) {
        WebClient webClient = webClient(
                new AtomicReference<>(),
                ClientResponse.create(HttpStatus.BAD_GATEWAY).body(" ").build());

        StepVerifier.create(clientCase.invocationFactory().apply(webClient).fetch(REQUEST, CLIENT_REQUEST_CONTEXT))
                .expectErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(DownstreamClientException.class)
                        .hasMessage(clientCase.errorPrefix() + " client failed: " + clientCase.defaultErrorMessage()))
                .verify();
    }

    @ParameterizedTest
    @MethodSource("clients")
    void fetch_rejectsNullClientRequestContext(WebClientCase clientCase) {
        WebClient webClient = webClient(
                new AtomicReference<>(),
                ClientResponse.create(HttpStatus.OK)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .body("{\"status\":\"ok\"}")
                        .build());
        WebClientInvocation invocation = clientCase.invocationFactory().apply(webClient);

        assertThatThrownBy(() -> invocation.fetch(REQUEST, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("ClientRequestContext must not be null");
    }

    @ParameterizedTest
    @MethodSource("clients")
    void fetch_mapsTransportErrorToDownstreamClientException(WebClientCase clientCase) {
        WebClient webClient = WebClient.builder()
                .baseUrl("https://client.example")
                .exchangeFunction(request -> Mono.error(new IOException("connection refused")))
                .filter(DownstreamClientErrorFilter.forClient(clientCase.errorPrefix()))
                .build();

        StepVerifier.create(webClient.post().uri(clientCase.path()).retrieve().bodyToMono(String.class))
                .expectErrorSatisfies(error -> {
                    assertThat(error)
                            .isInstanceOf(DownstreamClientException.class)
                            .hasMessage(
                                    clientCase.errorPrefix() + " client failed: " + clientCase.defaultErrorMessage())
                            .hasCauseInstanceOf(IOException.class);
                    DownstreamClientException clientException = (DownstreamClientException) error;
                    assertThat(clientException.clientName()).isEqualTo(clientCase.errorPrefix());
                    assertThat(clientException.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
                    assertThat(clientException.downstreamStatusCode()).isNull();
                    assertThat(clientException.responseBody()).isEqualTo(clientCase.defaultErrorMessage());
                })
                .verify();
    }

    private static Stream<WebClientCase> clients() {
        return Stream.of(
                new WebClientCase(
                        "/account-groups",
                        "Account group",
                        webClient -> httpClient(webClient, AccountGroups.class, "Account group")::fetchAccountGroup),
                new WebClientCase(
                        "/owners", "Owners", webClient -> httpClient(webClient, Owners.class, "Owners")::fetchOwners),
                new WebClientCase(
                        "/accounts",
                        "Account",
                        webClient -> httpClient(webClient, Accounts.class, "Account")::fetchAccounts));
    }

    private static WebClient webClient(AtomicReference<ClientRequest> capturedRequest, ClientResponse response) {
        return WebClient.builder()
                .baseUrl("https://client.example")
                .exchangeFunction(request -> {
                    capturedRequest.set(request);
                    return Mono.just(response);
                })
                .build();
    }

    private static <T> T httpClient(WebClient webClient, Class<T> clientType, String clientName) {
        WebClient filteredWebClient = webClient
                .mutate()
                .filter(DownstreamClientErrorFilter.forClient(clientName))
                .build();
        return HttpServiceProxyFactory.builderFor(WebClientAdapter.create(filteredWebClient))
                .customArgumentResolver(new ClientRequestContextHttpServiceArgumentResolver())
                .build()
                .createClient(clientType);
    }

    private record WebClientCase(
            String path, String errorPrefix, Function<WebClient, WebClientInvocation> invocationFactory) {

        String defaultErrorMessage() {
            return errorPrefix.substring(0, 1).toLowerCase() + errorPrefix.substring(1) + " client request failed";
        }
    }

    @FunctionalInterface
    private interface WebClientInvocation {
        Mono<JsonNode> fetch(ObjectNode request, ClientRequestContext clientRequestContext);
    }
}
