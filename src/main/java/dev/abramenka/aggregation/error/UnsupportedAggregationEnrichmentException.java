package dev.abramenka.aggregation.error;

import java.net.URI;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

public final class UnsupportedAggregationEnrichmentException extends ErrorResponseException {

    public static final URI TYPE = URI.create("/problems/unsupported-aggregation-enrichment");

    public UnsupportedAggregationEnrichmentException(List<String> enrichments) {
        super(HttpStatus.UNPROCESSABLE_CONTENT, problemDetail(enrichments), null);
    }

    @Override
    public String getMessage() {
        String detail = getBody().getDetail();
        return detail != null ? detail : super.getMessage();
    }

    private static ProblemDetail problemDetail(List<String> enrichments) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "Unsupported aggregation enrichment(s): " + String.join(", ", enrichments));
        body.setType(TYPE);
        body.setProperty("enrichments", List.copyOf(enrichments));
        return body;
    }
}
