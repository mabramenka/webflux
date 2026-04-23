package dev.abramenka.aggregation.error;

import java.util.List;

public final class RequestValidationException extends FacadeException {

    public static RequestValidationException invalidQueryParameter(String parameter, String message) {
        return new RequestValidationException(new ValidationError(pointer("query", parameter), message));
    }

    public static RequestValidationException invalidRequestValue(String field, String message) {
        return new RequestValidationException(new ValidationError(pointer("request", field), message));
    }

    private RequestValidationException(ValidationError error) {
        super(ProblemCatalog.CLIENT_VALIDATION, null, List.of(error), null);
    }

    private static String pointer(String location, String field) {
        return "/" + escape(location) + "/" + escape(field);
    }

    private static String escape(String value) {
        return value.replace("~", "~0").replace("/", "~1");
    }
}
