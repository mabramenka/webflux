package com.example.aggregation.api;

import com.example.aggregation.client.DownstreamClientException;
import com.example.aggregation.error.InvalidAggregationRequestException;
import java.net.URI;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    private static final URI TYPE_INVALID_AGGREGATION_REQUEST = URI.create("/problems/invalid-aggregation-request");
    private static final URI TYPE_DOWNSTREAM_CLIENT_ERROR = URI.create("/problems/downstream-client-error");
    private static final URI TYPE_INTERNAL_AGGREGATION_ERROR = URI.create("/problems/internal-aggregation-error");
    private static final String INTERNAL_ERROR_DETAIL = "Internal aggregation error";

    @ExceptionHandler(InvalidAggregationRequestException.class)
    public Mono<ResponseEntity<ProblemDetail>> handleInvalidAggregationRequest(
        InvalidAggregationRequestException ex,
        ServerWebExchange exchange
    ) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        applyProblemIdentifiers(detail, TYPE_INVALID_AGGREGATION_REQUEST, exchange);
        return Mono.just(
            ResponseEntity.badRequest()
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(detail)
        );
    }

    @ExceptionHandler(DownstreamClientException.class)
    public Mono<ResponseEntity<ProblemDetail>> handleDownstream(DownstreamClientException ex, ServerWebExchange exchange) {
        HttpStatus status = HttpStatus.resolve(ex.statusCode().value());
        if (status == null) {
            status = HttpStatus.BAD_GATEWAY;
        }
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(status, ex.getMessage());
        detail.setProperty("client", ex.clientName());
        applyProblemIdentifiers(detail, TYPE_DOWNSTREAM_CLIENT_ERROR, exchange);
        return Mono.just(
            ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(detail)
        );
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public Mono<ResponseEntity<ProblemDetail>> handleInternalAggregationError(Exception ex, ServerWebExchange exchange) {
        log.error(INTERNAL_ERROR_DETAIL, ex);
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(
            HttpStatus.INTERNAL_SERVER_ERROR,
            INTERNAL_ERROR_DETAIL
        );
        applyProblemIdentifiers(detail, TYPE_INTERNAL_AGGREGATION_ERROR, exchange);
        return Mono.just(
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(detail)
        );
    }

    private static void applyProblemIdentifiers(ProblemDetail detail, URI type, ServerWebExchange exchange) {
        detail.setType(type);
        detail.setInstance(exchange.getRequest().getURI());
    }
}
