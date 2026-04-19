package dev.abramenka.aggregation.error;

import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

public final class InvalidAggregationRequestException extends ErrorResponseException {

    private final String message;

    public InvalidAggregationRequestException(String message) {
        super(HttpStatus.BAD_REQUEST, problemDetail(message), null);
        this.message = message;
    }

    @Override
    public @NonNull String getMessage() {
        return message;
    }

    private static ProblemDetail problemDetail(String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        problem.setType(AggregationProblemTypes.INVALID_AGGREGATION_REQUEST);
        problem.setTitle("Invalid aggregation request");
        return problem;
    }
}
