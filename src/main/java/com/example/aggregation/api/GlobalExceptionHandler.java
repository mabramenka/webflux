package com.example.aggregation.api;

import com.example.aggregation.client.DownstreamClientException;
import com.example.aggregation.error.InvalidAggregationRequestException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import reactor.core.publisher.Mono;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(InvalidAggregationRequestException.class)
    public Mono<ResponseEntity<ProblemDetail>> handleInvalidAggregationRequest(InvalidAggregationRequestException ex) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        return Mono.just(
            ResponseEntity.badRequest()
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(detail)
        );
    }

    @ExceptionHandler(DownstreamClientException.class)
    public Mono<ResponseEntity<ProblemDetail>> handleDownstream(DownstreamClientException ex) {
        HttpStatus status = HttpStatus.resolve(ex.statusCode().value());
        if (status == null) {
            status = HttpStatus.BAD_GATEWAY;
        }
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(status, ex.getMessage());
        detail.setProperty("client", ex.clientName());
        return Mono.just(
            ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(detail)
        );
    }
}
