package dev.abramenka.aggregation.client;

import static dev.abramenka.aggregation.model.ForwardedHeaders.REQUEST_ID_HEADER;
import static org.assertj.core.api.Assertions.assertThat;

import dev.abramenka.aggregation.AggregationApplication;
import dev.abramenka.aggregation.model.ClientRequestContext;
import dev.abramenka.aggregation.model.ForwardedHeaders;
import dev.abramenka.aggregation.model.Projections;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.NettyOutbound;
import reactor.netty.http.server.HttpServer;
import reactor.netty.http.server.HttpServerRequest;
import reactor.test.StepVerifier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

@SpringBootTest(classes = AggregationApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
class HttpServiceClientConfigIntegrationTest {

    private static final ObjectNode REQUEST =
            JsonNodeFactory.instance.objectNode().put("id", "request-1");
    private static @Nullable DisposableServer server;

    @Autowired
    private AccountGroups accountGroups;

    @Autowired
    private Accounts accounts;

    @Autowired
    private Owners owners;

    @DynamicPropertySource
    static void serviceClientProperties(DynamicPropertyRegistry registry) {
        startServer();
        registry.add(
                "spring.http.serviceclient." + HttpServiceGroups.ACCOUNT_GROUP + ".base-url",
                HttpServiceClientConfigIntegrationTest::serverUrl);
        registry.add(
                "spring.http.serviceclient." + HttpServiceGroups.ACCOUNT + ".base-url",
                HttpServiceClientConfigIntegrationTest::serverUrl);
        registry.add(
                "spring.http.serviceclient." + HttpServiceGroups.OWNERS + ".base-url",
                HttpServiceClientConfigIntegrationTest::serverUrl);
    }

    @AfterAll
    static void stopServer() {
        DisposableServer runningServer = server;
        if (runningServer != null) {
            runningServer.disposeNow();
            server = null;
        }
    }

    @Test
    void bootConfiguresHttpServiceClientsFromServiceClientProperties() {
        StepVerifier.create(
                        accountGroups.fetchAccountGroup(REQUEST, AccountGroups.DEFAULT_FIELDS, clientRequestContext()))
                .assertNext(response -> assertClientResponse(response, "account-group"))
                .verifyComplete();

        StepVerifier.create(accounts.fetchAccounts(REQUEST, Accounts.DEFAULT_FIELDS, clientRequestContext()))
                .assertNext(response -> assertClientResponse(response, "account"))
                .verifyComplete();

        StepVerifier.create(owners.fetchOwners(REQUEST, Owners.DEFAULT_FIELDS, clientRequestContext()))
                .assertNext(response -> assertClientResponse(response, "owners"))
                .verifyComplete();
    }

    private static void startServer() {
        if (server != null) {
            return;
        }

        server = HttpServer.create()
                .host("localhost")
                .port(0)
                .route(routes -> routes.post(
                                "/account-groups",
                                (request, response) -> jsonResponse(request, response, "account-group"))
                        .post("/accounts", (request, response) -> jsonResponse(request, response, "account"))
                        .post("/owners", (request, response) -> jsonResponse(request, response, "owners")))
                .bindNow();
    }

    private static NettyOutbound jsonResponse(
            HttpServerRequest request, reactor.netty.http.server.HttpServerResponse response, String client) {
        String requestId = request.requestHeaders().get(REQUEST_ID_HEADER);
        return response.header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .sendString(Mono.just("""
                {"client":"%s","requestId":"%s","uri":"%s"}
                """.formatted(client, requestId, request.uri())));
    }

    private static String serverUrl() {
        return "http://localhost:" + Objects.requireNonNull(server).port();
    }

    private static ClientRequestContext clientRequestContext() {
        return new ClientRequestContext(
                ForwardedHeaders.builder().requestId("req-1").build(), true, Projections.empty());
    }

    private static void assertClientResponse(JsonNode response, String client) {
        assertThat(response.path("client").asString()).isEqualTo(client);
        assertThat(response.path("requestId").asString()).isEqualTo("req-1");
        assertThat(response.path("uri").asString()).contains("detokenize=true").contains("fields=");
    }
}
