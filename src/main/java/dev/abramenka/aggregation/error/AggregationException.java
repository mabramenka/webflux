package dev.abramenka.aggregation.error;

import java.net.URI;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

public abstract sealed class AggregationException extends ErrorResponseException
        permits DownstreamClientException, InvalidAggregationRequestException {

    protected AggregationException(HttpStatusCode status, ProblemDetail body, @Nullable Throwable cause) {
        super(status, body, cause);
    }

    protected static ProblemDetail problemDetail(HttpStatusCode status, URI type, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(type);
        problem.setTitle(title);
        return problem;
    }
}
