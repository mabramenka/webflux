package dev.abramenka.aggregation.error;

import java.net.URI;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

public final class UnsupportedAggregationPartException extends ErrorResponseException {

    public static final URI TYPE = URI.create("/problems/unsupported-aggregation-part");

    public UnsupportedAggregationPartException(List<String> parts) {
        super(HttpStatus.UNPROCESSABLE_CONTENT, problemDetail(parts), null);
    }

    @Override
    public String getMessage() {
        String detail = getBody().getDetail();
        return detail != null ? detail : super.getMessage();
    }

    private static ProblemDetail problemDetail(List<String> parts) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_CONTENT, "Unsupported aggregation part(s): " + String.join(", ", parts));
        body.setType(TYPE);
        body.setProperty("parts", List.copyOf(parts));
        return body;
    }
}
