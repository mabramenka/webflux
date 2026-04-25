package dev.abramenka.aggregation.error;

import java.util.ArrayList;
import java.util.List;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.core.MethodParameter;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.validation.method.MethodValidationResult;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.support.WebExchangeBindException;

final class ValidationErrorMapper {

    private ValidationErrorMapper() {}

    static List<ValidationError> collectBindingErrors(WebExchangeBindException ex) {
        return collectBindingErrors(ex.getFieldErrors(), ex.getGlobalErrors());
    }

    static List<ValidationError> collectMethodValidationErrors(MethodValidationResult validationResult) {
        List<ValidationError> errors = new ArrayList<>();
        validationResult
                .getParameterValidationResults()
                .forEach(result -> appendParameterValidationErrors(result, errors));
        return List.copyOf(errors);
    }

    private static List<ValidationError> collectBindingErrors(
            List<FieldError> fieldErrors, List<ObjectError> globalErrors) {
        List<ValidationError> errors = new ArrayList<>(fieldErrors.size() + globalErrors.size());
        fieldErrors.forEach(error -> errors.add(new ValidationError(bodyPointer(error.getField()), message(error))));
        globalErrors.forEach(error -> errors.add(new ValidationError("/", message(error))));
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
}
