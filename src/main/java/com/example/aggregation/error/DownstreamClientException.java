package com.example.aggregation.error;

import lombok.Getter;
import lombok.experimental.Accessors;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

public final class DownstreamClientException extends ErrorResponseException {

    @Getter
    @Accessors(fluent = true)
    private final String clientName;

    @Getter
    @Accessors(fluent = true)
    private final HttpStatusCode statusCode;

    @Nullable
    @Getter
    @Accessors(fluent = true)
    private final HttpStatusCode downstreamStatusCode;

    @Getter
    @Accessors(fluent = true)
    private final String responseBody;

    private final String message;

    public DownstreamClientException(String clientName, HttpStatusCode downstreamStatusCode, String responseBody) {
        this(clientName, HttpStatus.BAD_GATEWAY, downstreamStatusCode, responseBody, null);
    }

    public DownstreamClientException(
            String clientName, HttpStatusCode downstreamStatusCode, String responseBody, @Nullable Throwable cause) {
        this(clientName, HttpStatus.BAD_GATEWAY, downstreamStatusCode, responseBody, cause);
    }

    public static DownstreamClientException transportError(String clientName, String responseBody, Throwable cause) {
        return new DownstreamClientException(clientName, HttpStatus.BAD_GATEWAY, null, responseBody, cause);
    }

    public static DownstreamClientException gatewayError(String clientName, String responseBody) {
        return new DownstreamClientException(clientName, HttpStatus.BAD_GATEWAY, null, responseBody, null);
    }

    public static DownstreamClientException gatewayError(String clientName, String responseBody, Throwable cause) {
        return new DownstreamClientException(clientName, HttpStatus.BAD_GATEWAY, null, responseBody, cause);
    }

    private DownstreamClientException(
            String clientName,
            HttpStatusCode statusCode,
            @Nullable HttpStatusCode downstreamStatusCode,
            String responseBody,
            @Nullable Throwable cause) {
        super(statusCode, problemDetail(clientName, statusCode, downstreamStatusCode, responseBody), cause);
        this.clientName = clientName;
        this.statusCode = statusCode;
        this.downstreamStatusCode = downstreamStatusCode;
        this.responseBody = responseBody;
        this.message = clientName + " client failed: " + responseBody;
    }

    @Override
    public @NonNull String getMessage() {
        return message;
    }

    private static ProblemDetail problemDetail(
            String clientName,
            HttpStatusCode statusCode,
            @Nullable HttpStatusCode downstreamStatusCode,
            String responseBody) {
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(statusCode, clientName + " client failed: " + responseBody);
        problem.setType(AggregationProblemTypes.DOWNSTREAM_CLIENT_ERROR);
        problem.setTitle("Downstream client error");
        problem.setProperty("client", clientName);
        if (downstreamStatusCode != null) {
            problem.setProperty("downstreamStatus", downstreamStatusCode.value());
        }
        return problem;
    }
}
