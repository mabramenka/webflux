package dev.abramenka.aggregation.error;

import dev.abramenka.aggregation.model.ForwardedHeaders;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.validation.method.MethodValidationResult;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.reactive.result.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Mono;

@RestControllerAdvice
public final class AggregationErrorResponseAdvice extends ResponseEntityExceptionHandler {

    private static final URI INVALID_CONTENT_TYPE = URI.create("/problems/invalid-request-content");
    private static final URI INTERNAL_TYPE = URI.create("/problems/internal-aggregation-error");

    @Override
    protected Mono<ResponseEntity<Object>> handleWebExchangeBindException(
            WebExchangeBindException ex, HttpHeaders headers, HttpStatusCode status, ServerWebExchange exchange) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, "Invalid request content.");
        body.setType(RequestValidationException.TYPE);
        body.setProperty("errors", collectBindingErrors(ex.getFieldErrors(), ex.getGlobalErrors()));
        return handleExceptionInternal(ex, body, headers, status, exchange);
    }

    @Override
    protected Mono<ResponseEntity<Object>> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            ServerWebExchange exchange) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, "Request validation failed.");
        body.setType(RequestValidationException.TYPE);
        body.setProperty("errors", collectMethodValidationErrors(ex));
        return handleExceptionInternal(ex, body, headers, status, exchange);
    }

    @Override
    protected Mono<ResponseEntity<Object>> handleServerWebInputException(
            ServerWebInputException ex, HttpHeaders headers, HttpStatusCode status, ServerWebExchange exchange) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, "Request content is malformed or unreadable.");
        body.setType(INVALID_CONTENT_TYPE);
        return handleExceptionInternal(ex, body, headers, status, exchange);
    }

    // ErrorResponseException subclasses (DownstreamClientException, RequestValidationException,
    // UnsupportedAggregationPartException) are matched by the parent's more-specific
    // handleException first; this runs only for truly unexpected throwables.
    @ExceptionHandler(Exception.class)
    public Mono<ResponseEntity<Object>> handleUnexpectedException(Exception ex, ServerWebExchange exchange) {
        logger.error("Unhandled aggregation exception", ex);
        HttpStatusCode status = HttpStatus.INTERNAL_SERVER_ERROR;
        ProblemDetail body =
                ProblemDetail.forStatusAndDetail(status, "The aggregation request could not be completed.");
        body.setType(INTERNAL_TYPE);
        return handleExceptionInternal(ex, body, HttpHeaders.EMPTY, status, exchange);
    }

    @Override
    protected Mono<ResponseEntity<Object>> createResponseEntity(
            @Nullable Object body, @Nullable HttpHeaders headers, HttpStatusCode status, ServerWebExchange exchange) {
        if (body instanceof ProblemDetail problemDetail) {
            putIfPresent(problemDetail, "requestId", requestId(exchange));
            putIfPresent(
                    problemDetail,
                    "correlationId",
                    exchange.getRequest().getHeaders().getFirst(ForwardedHeaders.CORRELATION_ID_HEADER));
        }
        return super.createResponseEntity(body, headers, status, exchange);
    }

    private static List<ValidationError> collectBindingErrors(
            List<FieldError> fieldErrors, List<ObjectError> globalErrors) {
        List<ValidationError> errors = new ArrayList<>(fieldErrors.size() + globalErrors.size());
        fieldErrors.forEach(error -> errors.add(new ValidationError("body", error.getField(), message(error))));
        globalErrors.forEach(error -> errors.add(new ValidationError("body", null, message(error))));
        return List.copyOf(errors);
    }

    private static List<ValidationError> collectMethodValidationErrors(MethodValidationResult validationResult) {
        List<ValidationError> errors = new ArrayList<>();
        validationResult
                .getParameterValidationResults()
                .forEach(result -> appendParameterValidationErrors(result, errors));
        return List.copyOf(errors);
    }

    private static void appendParameterValidationErrors(
            ParameterValidationResult result, List<ValidationError> errors) {
        if (result instanceof ParameterErrors parameterErrors) {
            String location = parameterLocation(result.getMethodParameter());
            parameterErrors
                    .getFieldErrors()
                    .forEach(error -> errors.add(new ValidationError(location, error.getField(), message(error))));
            parameterErrors
                    .getGlobalErrors()
                    .forEach(error -> errors.add(
                            new ValidationError(location, parameterName(result.getMethodParameter()), message(error))));
            return;
        }

        String location = parameterLocation(result.getMethodParameter());
        String field = parameterPath(result);
        result.getResolvableErrors().forEach(error -> errors.add(new ValidationError(location, field, message(error))));
    }

    private static String parameterLocation(MethodParameter parameter) {
        if (parameter.hasParameterAnnotation(PathVariable.class)) {
            return "path";
        }
        if (parameter.hasParameterAnnotation(RequestParam.class)) {
            return "query";
        }
        if (parameter.hasParameterAnnotation(RequestHeader.class)
                || parameter.hasParameterAnnotation(CookieValue.class)) {
            return "header";
        }
        if (parameter.hasParameterAnnotation(RequestBody.class)) {
            return "body";
        }
        return "request";
    }

    private static String parameterPath(ParameterValidationResult result) {
        StringBuilder field = new StringBuilder(parameterName(result.getMethodParameter()));
        if (result.getContainerIndex() != null) {
            field.append('[').append(result.getContainerIndex()).append(']');
        }
        if (result.getContainerKey() != null) {
            field.append('[').append(result.getContainerKey()).append(']');
        }
        return field.toString();
    }

    private static String parameterName(MethodParameter parameter) {
        String name = parameter.getParameterName();
        if (name != null && !name.isBlank()) {
            return name;
        }
        return "arg" + parameter.getParameterIndex();
    }

    private static String message(MessageSourceResolvable error) {
        String detail = error.getDefaultMessage();
        return (detail == null || detail.isBlank()) ? "Validation failed." : detail;
    }

    private static @Nullable String requestId(ServerWebExchange exchange) {
        String responseHeader = exchange.getResponse().getHeaders().getFirst(ForwardedHeaders.REQUEST_ID_HEADER);
        if (responseHeader != null && !responseHeader.isBlank()) {
            return responseHeader;
        }
        String requestHeader = exchange.getRequest().getHeaders().getFirst(ForwardedHeaders.REQUEST_ID_HEADER);
        if (requestHeader != null && !requestHeader.isBlank()) {
            return requestHeader;
        }
        return null;
    }

    private static void putIfPresent(ProblemDetail detail, String name, @Nullable String value) {
        if (value != null && !value.isBlank()) {
            detail.setProperty(name, value);
        }
    }
}
