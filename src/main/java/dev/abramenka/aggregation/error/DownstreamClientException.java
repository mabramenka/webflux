package dev.abramenka.aggregation.error;

import lombok.Getter;
import lombok.experimental.Accessors;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

@Getter
@Accessors(fluent = true)
public final class DownstreamClientException extends AggregationException {

    private static final String TITLE = "Downstream client error";

    private final String clientName;

    @Nullable
    private final HttpStatusCode downstreamStatusCode;

    private final String responseBody;

    public DownstreamClientException(String clientName, HttpStatusCode downstreamStatusCode, String responseBody) {
        this(clientName, downstreamStatusCode, responseBody, null);
    }

    public static DownstreamClientException transportError(String clientName, String responseBody, Throwable cause) {
        return new DownstreamClientException(clientName, null, responseBody, cause);
    }

    public static DownstreamClientException gatewayError(String clientName, String responseBody) {
        return gatewayError(clientName, responseBody, null);
    }

    public static DownstreamClientException gatewayError(
            String clientName, String responseBody, @Nullable Throwable cause) {
        return new DownstreamClientException(clientName, null, responseBody, cause);
    }

    private DownstreamClientException(
            String clientName,
            @Nullable HttpStatusCode downstreamStatusCode,
            String responseBody,
            @Nullable Throwable cause) {
        super(HttpStatus.BAD_GATEWAY, buildProblemDetail(clientName, downstreamStatusCode, responseBody), cause);
        this.clientName = clientName;
        this.downstreamStatusCode = downstreamStatusCode;
        this.responseBody = responseBody;
    }

    private static ProblemDetail buildProblemDetail(
            String clientName, @Nullable HttpStatusCode downstreamStatusCode, String responseBody) {
        ProblemDetail problem = problemDetail(
                HttpStatus.BAD_GATEWAY,
                AggregationProblemTypes.DOWNSTREAM_CLIENT_ERROR,
                TITLE,
                clientName + " client failed: " + responseBody);
        problem.setProperty("client", clientName);
        if (downstreamStatusCode != null) {
            problem.setProperty("downstreamStatus", downstreamStatusCode.value());
        }
        return problem;
    }
}
