package com.example.aggregation;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class ActuatorEndpointsTest {

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void exposesHealthAndMetricsEndpoints() {
        webTestClient.get()
            .uri("/actuator/health")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.status").isEqualTo("UP");

        webTestClient.get()
            .uri("/actuator/metrics")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.names").isArray();
    }
}
