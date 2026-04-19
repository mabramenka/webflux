package dev.abramenka.aggregation.api;

import dev.abramenka.aggregation.error.AggregationProblemTypes;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    private static final String INTERNAL_ERROR_DETAIL = "Internal aggregation error";

    @ExceptionHandler(IllegalStateException.class)
    public ProblemDetail handleInternalAggregationError(IllegalStateException ex) {
        log.error(INTERNAL_ERROR_DETAIL, ex);
        ProblemDetail detail =
                ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, INTERNAL_ERROR_DETAIL);
        detail.setType(AggregationProblemTypes.INTERNAL_AGGREGATION_ERROR);
        detail.setTitle(INTERNAL_ERROR_DETAIL);
        return detail;
    }
}
