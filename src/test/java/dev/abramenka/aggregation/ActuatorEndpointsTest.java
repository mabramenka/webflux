package dev.abramenka.aggregation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class ActuatorEndpointsTest {

    @Autowired
    private WebTestClient webTestClient;

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness"})
    void exposedHealthEndpointsReportUp(String uri) {
        webTestClient
                .get()
                .uri(uri)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.status")
                .isEqualTo("UP");
    }

    @Test
    void exposesMetricsEndpoint() {
        webTestClient
                .get()
                .uri("/actuator/metrics")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.names")
                .isArray();
    }

    @Test
    void doesNotExposeUnapprovedActuatorEndpoints() {
        webTestClient.get().uri("/actuator/loggers").exchange().expectStatus().isNotFound();
    }
}
