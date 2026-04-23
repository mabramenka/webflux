package dev.abramenka.aggregation.error;

import dev.abramenka.aggregation.model.ForwardedHeaders;
import dev.abramenka.aggregation.model.TraceContext;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import org.jspecify.annotations.Nullable;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
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

    private static final String ERROR_CODE_PROPERTY = "errorCode";
    private static final String TIMESTAMP_PROPERTY = "timestamp";
    private static final String TRACE_ID_PROPERTY = "traceId";
    private static final String RETRY_AFTER_HEADER = "Retry-After";
    private static final String OVERLOAD_RETRY_AFTER_SECONDS = "1";

    @Override
    protected Mono<ResponseEntity<Object>> handleWebExchangeBindException(
            WebExchangeBindException ex, HttpHeaders headers, HttpStatusCode status, ServerWebExchange exchange) {
        ProblemDetail body = FacadeException.problemDetail(
                ProblemCatalog.CLIENT_VALIDATION,
                null,
                collectBindingErrors(ex.getFieldErrors(), ex.getGlobalErrors()));
        return handleExceptionInternal(ex, body, headers, status, exchange);
    }

    @Override
    protected Mono<ResponseEntity<Object>> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            ServerWebExchange exchange) {
        ProblemDetail body = FacadeException.problemDetail(
                ProblemCatalog.CLIENT_VALIDATION, null, collectMethodValidationErrors(ex));
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
        HttpHeaders responseHeaders = responseHeaders(headers, exchange);
        if (body instanceof ProblemDetail problemDetail) {
            ProblemDetail contractBody = contractBody(problemDetail, status);
            enrich(contractBody, exchange);
            return super.createResponseEntity(
                    contractBody, responseHeaders, HttpStatusCode.valueOf(contractBody.getStatus()), exchange);
        }
        return super.createResponseEntity(body, responseHeaders, status, exchange);
    }

    private static List<ValidationError> collectBindingErrors(
            List<FieldError> fieldErrors, List<ObjectError> globalErrors) {
        List<ValidationError> errors = new ArrayList<>(fieldErrors.size() + globalErrors.size());
        fieldErrors.forEach(error -> errors.add(new ValidationError(bodyPointer(error.getField()), message(error))));
        globalErrors.forEach(error -> errors.add(new ValidationError("/", message(error))));
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
                    .forEach(error ->
                            errors.add(new ValidationError(pointer(location, error.getField()), message(error))));
            parameterErrors
                    .getGlobalErrors()
                    .forEach(error -> errors.add(new ValidationError(
                            pointer(location, parameterName(result.getMethodParameter())), message(error))));
            return;
        }

        String location = parameterLocation(result.getMethodParameter());
        String field = parameterPath(result);
        result.getResolvableErrors()
                .forEach(error -> errors.add(new ValidationError(pointer(location, field), message(error))));
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

    private static ProblemDetail contractBody(ProblemDetail body, HttpStatusCode status) {
        if (body.getProperties() != null && body.getProperties().containsKey(ERROR_CODE_PROPERTY)) {
            return body;
        }
        ProblemCatalog catalog = catalogForFrameworkStatus(status);
        return FacadeException.problemDetail(catalog, null, List.of());
    }

    private static ProblemCatalog catalogForFrameworkStatus(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> ProblemCatalog.CLIENT_VALIDATION;
            case 401 -> ProblemCatalog.CLIENT_UNAUTHENTICATED;
            case 403 -> ProblemCatalog.CLIENT_FORBIDDEN;
            case 404 -> ProblemCatalog.CLIENT_NOT_FOUND;
            case 405 -> ProblemCatalog.CLIENT_METHOD_NOT_ALLOWED;
            case 406 -> ProblemCatalog.CLIENT_NOT_ACCEPTABLE;
            case 415 -> ProblemCatalog.CLIENT_UNSUPPORTED_MEDIA;
            case 429 -> ProblemCatalog.CLIENT_RATE_LIMITED;
            case 500 -> ProblemCatalog.PLATFORM_INTERNAL;
            default -> ProblemCatalog.PLATFORM_INTERNAL;
        };
    }

    private static void enrich(ProblemDetail detail, ServerWebExchange exchange) {
        String traceId = traceId(exchange);
        detail.setProperty(TRACE_ID_PROPERTY, traceId);
        detail.setProperty(TIMESTAMP_PROPERTY, Instant.now().toString());
        detail.setInstance(URI.create("/requests/" + uriSafe(traceId)));
    }

    private static HttpHeaders responseHeaders(@Nullable HttpHeaders headers, ServerWebExchange exchange) {
        HttpHeaders responseHeaders = new HttpHeaders();
        if (headers != null) {
            responseHeaders.putAll(headers);
        }
        putIfPresent(responseHeaders, TraceContext.TRACEPARENT_HEADER, traceparent(exchange));
        return responseHeaders;
    }

    private static String traceId(ServerWebExchange exchange) {
        String traceId = TraceContext.traceIdFromTraceparent(traceparent(exchange));
        if (traceId != null) {
            return traceId;
        }
        String requestId = requestId(exchange);
        return requestId != null ? requestId : TraceContext.newTraceId();
    }

    private static String traceparent(ServerWebExchange exchange) {
        String requestTraceparent =
                firstNonBlank(exchange.getRequest().getHeaders().getFirst(TraceContext.TRACEPARENT_HEADER));
        if (requestTraceparent != null && TraceContext.traceIdFromTraceparent(requestTraceparent) != null) {
            return requestTraceparent;
        }
        String responseTraceparent =
                firstNonBlank(exchange.getResponse().getHeaders().getFirst(TraceContext.TRACEPARENT_HEADER));
        if (responseTraceparent != null && TraceContext.traceIdFromTraceparent(responseTraceparent) != null) {
            return responseTraceparent;
        }
        String generated = TraceContext.newTraceparent();
        exchange.getResponse().getHeaders().set(TraceContext.TRACEPARENT_HEADER, generated);
        return generated;
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

    private static String bodyPointer(String field) {
        String pointer = field.replace("[", "/").replace("]", "");
        StringBuilder escaped = new StringBuilder(pointer.length() + 1);
        escaped.append('/');
        int segmentStart = 0;
        for (int i = 0; i <= pointer.length(); i++) {
            if (i == pointer.length() || pointer.charAt(i) == '/') {
                if (i > segmentStart) {
                    escaped.append(escape(pointer.substring(segmentStart, i)));
                }
                if (i < pointer.length()) {
                    escaped.append('/');
                }
                segmentStart = i + 1;
            }
        }
        return escaped.toString();
    }

    private static String pointer(String location, String field) {
        return "/" + escape(location) + "/" + escape(field);
    }

    private static String escape(String value) {
        return value.replace("~", "~0").replace("/", "~1");
    }

    private static String uriSafe(String value) {
        return value.replaceAll("[^A-Za-z0-9._~-]", "-");
    }

    private static @Nullable String firstNonBlank(@Nullable String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static void putIfPresent(HttpHeaders headers, String name, @Nullable String value) {
        if (value != null && !value.isBlank()) {
            headers.set(name, value);
        }
    }
}
