package dev.abramenka.aggregation.error;

import java.net.URI;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

public final class RequestValidationException extends ErrorResponseException {

    public static final URI TYPE = URI.create("/problems/request-validation-failed");

    public static RequestValidationException invalidQueryParameter(String parameter, String message) {
        return new RequestValidationException(new ValidationError("query", parameter, message));
    }

    public static RequestValidationException invalidRequestValue(String field, String message) {
        return new RequestValidationException(new ValidationError("request", field, message));
    }

    private RequestValidationException(ValidationError error) {
        super(HttpStatus.BAD_REQUEST, problemDetail(error), null);
    }

    @Override
    public String getMessage() {
        String detail = getBody().getDetail();
        return detail != null ? detail : super.getMessage();
    }

    private static ProblemDetail problemDetail(ValidationError error) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request validation failed.");
        body.setType(TYPE);
        body.setProperty("errors", List.of(error));
        return body;
    }
}
