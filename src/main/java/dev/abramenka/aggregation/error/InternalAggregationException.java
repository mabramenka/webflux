package dev.abramenka.aggregation.error;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;

public final class InternalAggregationException extends AggregationException {

    private static final String TITLE = "Internal aggregation error";

    public InternalAggregationException(String detail, @Nullable Throwable cause) {
        super(
                HttpStatus.INTERNAL_SERVER_ERROR,
                problemDetail(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        AggregationProblemTypes.INTERNAL_AGGREGATION_ERROR,
                        TITLE,
                        detail),
                cause);
    }
}
