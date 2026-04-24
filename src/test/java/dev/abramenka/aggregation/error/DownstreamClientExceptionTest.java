package dev.abramenka.aggregation.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

class DownstreamClientExceptionTest {

    @Test
    void upstreamStatus_usesGatewayStatusAndPreservesDownstreamStatus() {
        DownstreamClientException exception =
                DownstreamClientException.upstreamStatus("Account", HttpStatus.BAD_REQUEST);

        ProblemDetail body = exception.getBody();

        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(exception.downstreamStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(body.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY.value());
        assertThat(body.getType()).isEqualTo(ProblemCatalog.ENRICH_BAD_RESPONSE.type());
        assertThat(body.getDetail()).isEqualTo(ProblemCatalog.ENRICH_BAD_RESPONSE.defaultDetail());
        assertThat(body.getProperties())
                .containsEntry("errorCode", "ENRICH-BAD-RESPONSE")
                .containsEntry("category", "ENRICHMENT_DEPENDENCY")
                .containsEntry("dependency", "enricher:account")
                .containsEntry("retryable", false)
                .doesNotContainKeys("downstreamStatus", "client");
    }

    @Test
    void transport_withoutCauseHasNoDownstreamStatus() {
        DownstreamClientException exception = DownstreamClientException.transport("Account group", null);

        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
        assertThat(exception.downstreamStatusCode()).isNull();
        assertThat(exception.getCause()).isNull();
        assertThat(exception.getBody().getType()).isEqualTo(ProblemCatalog.MAIN_UNAVAILABLE.type());
        assertThat(exception.getBody().getDetail()).isEqualTo(ProblemCatalog.MAIN_UNAVAILABLE.defaultDetail());
        assertThat(exception.getBody().getProperties())
                .containsEntry("errorCode", "MAIN-UNAVAILABLE")
                .containsEntry("category", "MAIN_DEPENDENCY")
                .containsEntry("dependency", "main")
                .containsEntry("retryable", true)
                .doesNotContainKey("downstreamStatus");
    }

    @Test
    void transport_withCauseKeepsItAndHasNoDownstreamStatus() {
        IOException cause = new IOException("connection refused");

        DownstreamClientException exception = DownstreamClientException.transport("Owners", cause);

        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
        assertThat(exception.downstreamStatusCode()).isNull();
        assertThat(exception.getCause()).isSameAs(cause);
        assertThat(exception.getBody().getDetail()).isEqualTo(ProblemCatalog.ENRICH_UNAVAILABLE.defaultDetail());
        assertThat(exception.getBody().getProperties())
                .containsEntry("errorCode", "ENRICH-UNAVAILABLE")
                .containsEntry("dependency", "enricher:owners")
                .doesNotContainKey("downstreamStatus");
    }

    @Test
    void upstreamStatus_mapsDependencyAuthFailureToFacadeAuthProblem() {
        DownstreamClientException exception =
                DownstreamClientException.upstreamStatus("Account group", HttpStatus.UNAUTHORIZED);

        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(exception.getBody().getType()).isEqualTo(ProblemCatalog.MAIN_AUTH_FAILED.type());
        assertThat(exception.getBody().getProperties())
                .containsEntry("errorCode", "MAIN-AUTH-FAILED")
                .containsEntry("dependency", "main");
    }

    @Test
    void upstreamStatus_mapsMainNotFoundToOpaqueBadResponse() {
        DownstreamClientException exception =
                DownstreamClientException.upstreamStatus("Account group", HttpStatus.NOT_FOUND);

        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(exception.downstreamStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exception.getBody().getType()).isEqualTo(ProblemCatalog.MAIN_BAD_RESPONSE.type());
        assertThat(exception.getBody().getProperties()).containsEntry("dependency", "main");
    }

    @Test
    void upstreamStatus_mapsEscapedEnrichmentNotFoundToBadResponse() {
        DownstreamClientException exception = DownstreamClientException.upstreamStatus("Owners", HttpStatus.NOT_FOUND);

        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(exception.getBody().getType()).isEqualTo(ProblemCatalog.ENRICH_BAD_RESPONSE.type());
        assertThat(exception.getBody().getProperties()).containsEntry("dependency", "enricher:owners");
    }
}
