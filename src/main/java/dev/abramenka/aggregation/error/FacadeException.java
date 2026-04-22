package dev.abramenka.aggregation.error;

import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

public abstract sealed class FacadeException extends ErrorResponseException
        permits DownstreamClientException,
                EnrichmentDependencyException,
                OrchestrationException,
                PlatformException,
                RequestValidationException,
                UnsupportedAggregationPartException {

    private final ProblemCatalog catalog;

    protected FacadeException(ProblemCatalog catalog, @Nullable String dependency, @Nullable Throwable cause) {
        this(catalog, dependency, List.of(), cause);
    }

    protected FacadeException(
            ProblemCatalog catalog,
            @Nullable String dependency,
            List<ValidationError> violations,
            @Nullable Throwable cause) {
        super(catalog.status(), problemDetail(catalog, dependency, violations), cause);
        this.catalog = catalog;
    }

    public ProblemCatalog catalog() {
        return catalog;
    }

    @Override
    public String getMessage() {
        String detail = getBody().getDetail();
        return detail != null ? detail : super.getMessage();
    }

    static ProblemDetail problemDetail(
            ProblemCatalog catalog, @Nullable String dependency, List<ValidationError> violations) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(catalog.status(), catalog.defaultDetail());
        body.setType(catalog.type());
        body.setTitle(catalog.title());
        body.setProperty("errorCode", catalog.errorCode());
        body.setProperty("category", catalog.category().name());
        body.setProperty("retryable", catalog.retryable());
        if (dependency != null && !dependency.isBlank()) {
            body.setProperty("dependency", dependency);
        }
        if (!violations.isEmpty()) {
            body.setProperty("violations", List.copyOf(violations));
        }
        return body;
    }
}
