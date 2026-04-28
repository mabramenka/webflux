package dev.abramenka.aggregation.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import dev.abramenka.aggregation.error.DownstreamClientException;
import dev.abramenka.aggregation.error.UnsupportedAggregationPartException;
import dev.abramenka.aggregation.model.ClientRequestContext;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.ErrorResponseException;
import reactor.core.publisher.Mono;

class AggregateControllerProblemMappingTest extends AggregateControllerWebFluxTestSupport {

    @Test
    void aggregate_returnsProblemDetailWhenServiceRejectsRequest() {
        when(aggregateService.aggregate(any(AggregateRequest.class), any(ClientRequestContext.class)))
                .thenReturn(Mono.error(new UnsupportedAggregationPartException()));

        webTestClient
                .post()
                .uri("/api/v1/aggregate")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                {"ids":["AB123456789"]}
                """)
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.type")
                .isEqualTo("/problems/validation")
                .jsonPath("$.status")
                .isEqualTo(HttpStatus.BAD_REQUEST.value())
                .jsonPath("$.errorCode")
                .isEqualTo("CLIENT-VALIDATION")
                .jsonPath("$.detail")
                .isEqualTo("One or more request fields failed validation.")
                .jsonPath("$.violations[0].pointer")
                .isEqualTo("/request/include")
                .jsonPath("$.instance")
                .value(instance -> assertThat((String) instance).startsWith("/requests/"));
    }

    @Test
    void aggregate_returnsInternalProblemDetailWhenUnexpectedExceptionEscapes() {
        when(aggregateService.aggregate(any(AggregateRequest.class), any(ClientRequestContext.class)))
                .thenReturn(Mono.error(new IllegalStateException("boom")));

        webTestClient
                .post()
                .uri("/api/v1/aggregate")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                {"ids":["AB123456789"]}
                """)
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR)
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.type")
                .isEqualTo("/problems/platform/internal")
                .jsonPath("$.errorCode")
                .isEqualTo("PLATFORM-INTERNAL")
                .jsonPath("$.category")
                .isEqualTo("PLATFORM")
                .jsonPath("$.detail")
                .isEqualTo("The service encountered an unexpected internal error.");
    }

    @Test
    void aggregate_returnsInternalProblemDetailWhenThrowableEscapes() {
        when(aggregateService.aggregate(any(AggregateRequest.class), any(ClientRequestContext.class)))
                .thenReturn(Mono.error(new LinkageError("linkage failure")));

        webTestClient
                .post()
                .uri("/api/v1/aggregate")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                {"ids":["AB123456789"]}
                """)
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR)
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.type")
                .isEqualTo("/problems/platform/internal")
                .jsonPath("$.errorCode")
                .isEqualTo("PLATFORM-INTERNAL")
                .jsonPath("$.detail")
                .isEqualTo("The service encountered an unexpected internal error.");
    }

    @Test
    void aggregate_returnsProblemDetailWhenDownstreamFails() {
        when(aggregateService.aggregate(any(AggregateRequest.class), any(ClientRequestContext.class)))
                .thenReturn(Mono.error(DownstreamClientException.upstreamStatus("Account", HttpStatus.BAD_REQUEST)));

        webTestClient
                .post()
                .uri("/api/v1/aggregate")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                {"ids":["AB123456789"]}
                """)
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.BAD_GATEWAY)
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.type")
                .isEqualTo("/problems/enrichment/bad-response")
                .jsonPath("$.detail")
                .isEqualTo("A required enrichment dependency returned an unexpected response status.")
                .jsonPath("$.errorCode")
                .isEqualTo("ENRICH-BAD-RESPONSE")
                .jsonPath("$.dependency")
                .isEqualTo("enricher:account")
                .jsonPath("$.downstreamStatus")
                .doesNotExist();
    }

    @Test
    void aggregate_mapsFrameworkUnauthorizedToCatalogEntry() {
        when(aggregateService.aggregate(any(AggregateRequest.class), any(ClientRequestContext.class)))
                .thenReturn(Mono.error(new ErrorResponseException(HttpStatus.UNAUTHORIZED)));

        webTestClient
                .post()
                .uri("/api/v1/aggregate")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                {"ids":["AB123456789"]}
                """)
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectBody()
                .jsonPath("$.type")
                .isEqualTo("/problems/unauthenticated")
                .jsonPath("$.errorCode")
                .isEqualTo("CLIENT-UNAUTHENTICATED");
    }

    @Test
    void aggregate_mapsFrameworkForbiddenToCatalogEntry() {
        when(aggregateService.aggregate(any(AggregateRequest.class), any(ClientRequestContext.class)))
                .thenReturn(Mono.error(new ErrorResponseException(HttpStatus.FORBIDDEN)));

        webTestClient
                .post()
                .uri("/api/v1/aggregate")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                {"ids":["AB123456789"]}
                """)
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody()
                .jsonPath("$.type")
                .isEqualTo("/problems/forbidden")
                .jsonPath("$.errorCode")
                .isEqualTo("CLIENT-FORBIDDEN");
    }

    @Test
    void aggregate_mapsFrameworkRateLimitToCatalogEntry() {
        when(aggregateService.aggregate(any(AggregateRequest.class), any(ClientRequestContext.class)))
                .thenReturn(Mono.error(new ErrorResponseException(HttpStatus.TOO_MANY_REQUESTS)));

        webTestClient
                .post()
                .uri("/api/v1/aggregate")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                {"ids":["AB123456789"]}
                """)
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
                .expectBody()
                .jsonPath("$.type")
                .isEqualTo("/problems/rate-limited")
                .jsonPath("$.errorCode")
                .isEqualTo("CLIENT-RATE-LIMITED")
                .jsonPath("$.retryable")
                .isEqualTo(true);
    }

    @Test
    void aggregate_mapsRejectedExecutionToOverloadedProblem() {
        when(aggregateService.aggregate(any(AggregateRequest.class), any(ClientRequestContext.class)))
                .thenReturn(Mono.error(new RejectedExecutionException("pool exhausted")));

        webTestClient
                .post()
                .uri("/api/v1/aggregate")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                {"ids":["AB123456789"]}
                """)
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectHeader()
                .valueEquals(HttpHeaders.RETRY_AFTER, "1")
                .expectBody()
                .jsonPath("$.type")
                .isEqualTo("/problems/platform/overloaded")
                .jsonPath("$.errorCode")
                .isEqualTo("PLATFORM-OVERLOADED")
                .jsonPath("$.retryable")
                .isEqualTo(true);
    }

    @Test
    void aggregate_mapsUnknownFrameworkStatusToPlatformInternal() {
        when(aggregateService.aggregate(any(AggregateRequest.class), any(ClientRequestContext.class)))
                .thenReturn(Mono.error(new ErrorResponseException(HttpStatus.CONFLICT)));

        webTestClient
                .post()
                .uri("/api/v1/aggregate")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                {"ids":["AB123456789"]}
                """)
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR)
                .expectBody()
                .jsonPath("$.type")
                .isEqualTo("/problems/platform/internal")
                .jsonPath("$.errorCode")
                .isEqualTo("PLATFORM-INTERNAL");
    }
}
