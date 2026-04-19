package dev.abramenka.aggregation;

import static org.assertj.core.api.Assertions.assertThat;

import dev.abramenka.aggregation.client.HttpServiceGroups;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class AggregateDownstreamBodyErrorIntegrationTest {

    private static DisposableServer server;

    @Autowired
    private WebTestClient webTestClient;

    @DynamicPropertySource
    static void serviceClientProperties(DynamicPropertyRegistry registry) {
        startServer();
        registry.add(
                "spring.http.serviceclient." + HttpServiceGroups.ACCOUNT_GROUP + ".base-url",
                AggregateDownstreamBodyErrorIntegrationTest::serverUrl);
        registry.add(
                "spring.http.serviceclient." + HttpServiceGroups.ACCOUNT + ".base-url",
                AggregateDownstreamBodyErrorIntegrationTest::serverUrl);
        registry.add(
                "spring.http.serviceclient." + HttpServiceGroups.OWNERS + ".base-url",
                AggregateDownstreamBodyErrorIntegrationTest::serverUrl);
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.disposeNow();
        }
    }

    @Test
    void aggregate_returnsDownstreamProblemWhenAccountGroupBodyIsUnreadable() {
        webTestClient
                .post()
                .uri("/api/v1/aggregate")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                {"ids":["id-x19"],"include":[]}
                """)
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.BAD_GATEWAY)
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.type")
                .isEqualTo("/problems/downstream-client-error")
                .jsonPath("$.client")
                .isEqualTo("Account group")
                .jsonPath("$.detail")
                .value(detail -> assertThat(detail.toString()).contains("unreadable response"));
    }

    private static void startServer() {
        if (server != null) {
            return;
        }

        server = HttpServer.create()
                .host("localhost")
                .port(0)
                .route(routes -> routes.post("/account-groups", (request, response) -> response.header(
                                HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .sendString(Mono.just("{"))))
                .bindNow();
    }

    private static String serverUrl() {
        return "http://localhost:" + server.port();
    }
}
