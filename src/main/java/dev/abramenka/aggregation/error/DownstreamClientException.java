package dev.abramenka.aggregation.error;

import java.net.URI;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

public final class DownstreamClientException extends ErrorResponseException {

    public static final URI TYPE = URI.create("/problems/downstream-client-error");

    private final String clientName;

    @Nullable
    private final HttpStatusCode downstreamStatusCode;

    public static DownstreamClientException upstreamStatus(String clientName, HttpStatusCode status) {
        return new DownstreamClientException(clientName, status, null);
    }

    public static DownstreamClientException transport(String clientName, @Nullable Throwable cause) {
        return new DownstreamClientException(clientName, null, cause);
    }

    private DownstreamClientException(
            String clientName, @Nullable HttpStatusCode downstreamStatusCode, @Nullable Throwable cause) {
        super(HttpStatus.BAD_GATEWAY, problemDetail(clientName, downstreamStatusCode), cause);
        this.clientName = clientName;
        this.downstreamStatusCode = downstreamStatusCode;
    }

    public String clientName() {
        return clientName;
    }

    public @Nullable HttpStatusCode downstreamStatusCode() {
        return downstreamStatusCode;
    }

    @Override
    public String getMessage() {
        String detail = getBody().getDetail();
        return detail != null ? detail : super.getMessage();
    }

    private static ProblemDetail problemDetail(String clientName, @Nullable HttpStatusCode downstreamStatusCode) {
        String detail = downstreamStatusCode != null
                ? clientName + " client returned an error response"
                : clientName + " client request failed";
        ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, detail);
        body.setType(TYPE);
        body.setProperty("client", clientName);
        if (downstreamStatusCode != null) {
            body.setProperty("downstreamStatus", downstreamStatusCode.value());
        }
        return body;
    }
}
