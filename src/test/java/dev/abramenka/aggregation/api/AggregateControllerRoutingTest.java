package dev.abramenka.aggregation.api;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

class AggregateControllerRoutingTest extends AggregateControllerWebFluxTestSupport {

    @Test
    void aggregate_rejectsUnsupportedApiVersion() {
        webTestClient
                .get()
                .uri("/api/v99/aggregate/AB123456789")
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
    }

    @Test
    void aggregate_rejectsMethodNotAllowed() {
        webTestClient
                .get()
                .uri("/api/v1/aggregate")
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.METHOD_NOT_ALLOWED)
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.type")
                .isEqualTo("/problems/method-not-allowed")
                .jsonPath("$.errorCode")
                .isEqualTo("CLIENT-METHOD-NOT-ALLOWED")
                .jsonPath("$.status")
                .isEqualTo(HttpStatus.METHOD_NOT_ALLOWED.value());
    }

    @Test
    void aggregate_rejectsUnsupportedAcceptHeader() {
        webTestClient
                .post()
                .uri("/api/v1/aggregate")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_XML)
                .bodyValue("""
                {"ids":["AB123456789"]}
                """)
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.NOT_ACCEPTABLE)
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.type")
                .isEqualTo("/problems/not-acceptable")
                .jsonPath("$.errorCode")
                .isEqualTo("CLIENT-NOT-ACCEPTABLE")
                .jsonPath("$.status")
                .isEqualTo(HttpStatus.NOT_ACCEPTABLE.value());
    }

    @Test
    void aggregate_rejectsUnsupportedContentType() {
        webTestClient
                .post()
                .uri("/api/v1/aggregate")
                .contentType(MediaType.TEXT_PLAIN)
                .bodyValue("ids=AB123456789")
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.type")
                .isEqualTo("/problems/unsupported-media")
                .jsonPath("$.errorCode")
                .isEqualTo("CLIENT-UNSUPPORTED-MEDIA")
                .jsonPath("$.status")
                .isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE.value());
    }

    @Test
    void aggregate_returnsProblemDetailWhenResourceIsNotFound() {
        webTestClient
                .get()
                .uri("/api/v1/does-not-exist")
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.NOT_FOUND)
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
    }
}
