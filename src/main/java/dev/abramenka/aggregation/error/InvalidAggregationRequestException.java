package dev.abramenka.aggregation.error;

import org.springframework.http.HttpStatus;

public final class InvalidAggregationRequestException extends AggregationException {

    private static final String TITLE = "Invalid aggregation request";

    public InvalidAggregationRequestException(String message) {
        super(
                HttpStatus.BAD_REQUEST,
                problemDetail(
                        HttpStatus.BAD_REQUEST, AggregationProblemTypes.INVALID_AGGREGATION_REQUEST, TITLE, message),
                null);
    }
}
