package com.example.aggregation.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

class DownstreamClientExceptionTest {

    @Test
    void downstreamHttpError_usesGatewayStatusAndPreservesDownstreamStatus() {
        DownstreamClientException exception =
                new DownstreamClientException("Account", HttpStatus.BAD_REQUEST, "bad account request");

        ProblemDetail body = exception.getBody();

        assertThat(exception.statusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(exception.downstreamStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(body.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY.value());
        assertThat(body.getProperties())
                .containsEntry("downstreamStatus", HttpStatus.BAD_REQUEST.value())
                .containsEntry("client", "Account");
    }

    @Test
    void gatewayError_hasNoDownstreamStatus() {
        DownstreamClientException exception = DownstreamClientException.gatewayError(
                "Account group", "account group client returned a non-object JSON response");

        assertThat(exception.statusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(exception.downstreamStatusCode()).isNull();
        assertThat(exception.getBody().getProperties()).doesNotContainKey("downstreamStatus");
    }

    @Test
    void transportError_hasNoDownstreamStatusAndKeepsCause() {
        IOException cause = new IOException("connection refused");

        DownstreamClientException exception =
                DownstreamClientException.transportError("Owners", "owners client request failed", cause);

        assertThat(exception.statusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(exception.downstreamStatusCode()).isNull();
        assertThat(exception.getCause()).isSameAs(cause);
        assertThat(exception.getBody().getProperties()).doesNotContainKey("downstreamStatus");
    }
}
