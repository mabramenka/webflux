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
        assertThat(body.getType()).isEqualTo(DownstreamClientException.TYPE);
        assertThat(body.getDetail()).isEqualTo("Account client returned an error response");
        assertThat(body.getProperties())
                .containsEntry("downstreamStatus", HttpStatus.BAD_REQUEST.value())
                .containsEntry("client", "Account");
    }

    @Test
    void transport_withoutCauseHasNoDownstreamStatus() {
        DownstreamClientException exception = DownstreamClientException.transport("Account group", null);

        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(exception.downstreamStatusCode()).isNull();
        assertThat(exception.getCause()).isNull();
        assertThat(exception.getBody().getType()).isEqualTo(DownstreamClientException.TYPE);
        assertThat(exception.getBody().getDetail()).isEqualTo("Account group client request failed");
        assertThat(exception.getBody().getProperties()).doesNotContainKey("downstreamStatus");
    }

    @Test
    void transport_withCauseKeepsItAndHasNoDownstreamStatus() {
        IOException cause = new IOException("connection refused");

        DownstreamClientException exception = DownstreamClientException.transport("Owners", cause);

        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(exception.downstreamStatusCode()).isNull();
        assertThat(exception.getCause()).isSameAs(cause);
        assertThat(exception.getBody().getDetail()).isEqualTo("Owners client request failed");
        assertThat(exception.getBody().getProperties()).doesNotContainKey("downstreamStatus");
    }
}
