package dev.abramenka.aggregation.error;

import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.reactive.result.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Mono;

@RestControllerAdvice
final class AggregationErrorResponseAdvice extends ResponseEntityExceptionHandler {

    private static final String ERROR_CODE_PROPERTY = "errorCode";
    private static final String RETRY_AFTER_HEADER = "Retry-After";
    private static final String OVERLOAD_RETRY_AFTER_SECONDS = "1";

    @Override
    protected Mono<ResponseEntity<Object>> handleWebExchangeBindException(
            WebExchangeBindException ex, HttpHeaders headers, HttpStatusCode status, ServerWebExchange exchange) {
        ProblemDetail body = FacadeException.problemDetail(
                ProblemCatalog.CLIENT_VALIDATION, null, ValidationErrorMapper.collectBindingErrors(ex));
        return handleExceptionInternal(ex, body, headers, status, exchange);
    }

    @Override
    protected Mono<ResponseEntity<Object>> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            ServerWebExchange exchange) {
        ProblemDetail body = FacadeException.problemDetail(
                ProblemCatalog.CLIENT_VALIDATION, null, ValidationErrorMapper.collectMethodValidationErrors(ex));
        return handleExceptionInternal(ex, body, headers, status, exchange);
    }

    @Override
    protected Mono<ResponseEntity<Object>> handleServerWebInputException(
            ServerWebInputException ex, HttpHeaders headers, HttpStatusCode status, ServerWebExchange exchange) {
        ProblemDetail body = FacadeException.problemDetail(ProblemCatalog.CLIENT_INVALID_BODY, null, List.of());
        return handleExceptionInternal(ex, body, headers, status, exchange);
    }

    // ErrorResponseException subclasses are matched by the parent's more-specific
    // handleException first; this runs only for truly unexpected throwables.
    @ExceptionHandler(Throwable.class)
    public Mono<ResponseEntity<Object>> handleUnexpectedThrowable(Throwable ex, ServerWebExchange exchange) {
        logger.error("Unhandled aggregation exception", ex);
        HttpStatusCode status = ProblemCatalog.PLATFORM_INTERNAL.status();
        ProblemDetail body = FacadeException.problemDetail(ProblemCatalog.PLATFORM_INTERNAL, null, List.of());
        return createResponseEntity(body, HttpHeaders.EMPTY, status, exchange);
    }

    @ExceptionHandler(RejectedExecutionException.class)
    public Mono<ResponseEntity<Object>> handleRejectedExecutionException(
            RejectedExecutionException ex, ServerWebExchange exchange) {
        logger.warn("Aggregation request rejected because the service is overloaded", ex);
        HttpHeaders headers = new HttpHeaders();
        headers.set(RETRY_AFTER_HEADER, OVERLOAD_RETRY_AFTER_SECONDS);
        PlatformException overloaded = PlatformException.overloaded(ex);
        return handleExceptionInternal(ex, overloaded.getBody(), headers, overloaded.getStatusCode(), exchange);
    }

    @Override
    protected Mono<ResponseEntity<Object>> createResponseEntity(
            @Nullable Object body, @Nullable HttpHeaders headers, HttpStatusCode status, ServerWebExchange exchange) {
        HttpHeaders responseHeaders = ProblemResponseSupport.responseHeaders(headers, exchange);
        if (body instanceof ProblemDetail problemDetail) {
            ProblemDetail contractBody = contractBody(problemDetail, status);
            ProblemResponseSupport.enrich(contractBody, exchange);
            return super.createResponseEntity(
                    contractBody, responseHeaders, HttpStatusCode.valueOf(contractBody.getStatus()), exchange);
        }
        return super.createResponseEntity(body, responseHeaders, status, exchange);
    }

    private static ProblemDetail contractBody(ProblemDetail body, HttpStatusCode status) {
        if (body.getProperties() != null && body.getProperties().containsKey(ERROR_CODE_PROPERTY)) {
            return body;
        }
        ProblemCatalog catalog = ProblemResponseSupport.catalogForFrameworkStatus(status);
        return FacadeException.problemDetail(catalog, null, List.of());
    }
}
