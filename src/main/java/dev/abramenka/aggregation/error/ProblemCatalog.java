package dev.abramenka.aggregation.error;

import java.net.URI;
import org.springframework.http.HttpStatus;

public enum ProblemCatalog {
    CLIENT_VALIDATION(
            "CLIENT-VALIDATION",
            "/problems/validation",
            "Request validation failed",
            HttpStatus.BAD_REQUEST,
            ProblemCategory.CLIENT_REQUEST,
            false,
            "One or more request fields failed validation."),
    CLIENT_UNAUTHENTICATED(
            "CLIENT-UNAUTHENTICATED",
            "/problems/unauthenticated",
            "Authentication required",
            HttpStatus.UNAUTHORIZED,
            ProblemCategory.CLIENT_REQUEST,
            false,
            "Authentication is required to complete the request."),
    CLIENT_FORBIDDEN(
            "CLIENT-FORBIDDEN",
            "/problems/forbidden",
            "Access denied",
            HttpStatus.FORBIDDEN,
            ProblemCategory.CLIENT_REQUEST,
            false,
            "The authenticated principal is not allowed to complete the request."),
    CLIENT_NOT_FOUND(
            "CLIENT-NOT-FOUND",
            "/problems/not-found",
            "Resource not found",
            HttpStatus.NOT_FOUND,
            ProblemCategory.CLIENT_REQUEST,
            false,
            "The requested resource was not found."),
    CLIENT_METHOD_NOT_ALLOWED(
            "CLIENT-METHOD-NOT-ALLOWED",
            "/problems/method-not-allowed",
            "Method not allowed",
            HttpStatus.METHOD_NOT_ALLOWED,
            ProblemCategory.CLIENT_REQUEST,
            false,
            "The request method is not supported for this resource."),
    CLIENT_NOT_ACCEPTABLE(
            "CLIENT-NOT-ACCEPTABLE",
            "/problems/not-acceptable",
            "Not acceptable",
            HttpStatus.NOT_ACCEPTABLE,
            ProblemCategory.CLIENT_REQUEST,
            false,
            "The requested response media type is not supported."),
    CLIENT_UNSUPPORTED_MEDIA(
            "CLIENT-UNSUPPORTED-MEDIA",
            "/problems/unsupported-media",
            "Unsupported media type",
            HttpStatus.UNSUPPORTED_MEDIA_TYPE,
            ProblemCategory.CLIENT_REQUEST,
            false,
            "The request media type is not supported."),
    CLIENT_RATE_LIMITED(
            "CLIENT-RATE-LIMITED",
            "/problems/rate-limited",
            "Rate limit exceeded",
            HttpStatus.TOO_MANY_REQUESTS,
            ProblemCategory.CLIENT_REQUEST,
            true,
            "The request rate limit has been exceeded."),
    MAIN_TIMEOUT(
            "MAIN-TIMEOUT",
            "/problems/main/timeout",
            "Main dependency timed out",
            HttpStatus.GATEWAY_TIMEOUT,
            ProblemCategory.MAIN_DEPENDENCY,
            true,
            "The main dependency call did not complete within the allowed time."),
    MAIN_UNAVAILABLE(
            "MAIN-UNAVAILABLE",
            "/problems/main/unavailable",
            "Main dependency unavailable",
            HttpStatus.GATEWAY_TIMEOUT,
            ProblemCategory.MAIN_DEPENDENCY,
            true,
            "The main dependency is currently unavailable."),
    MAIN_BAD_RESPONSE(
            "MAIN-BAD-RESPONSE",
            "/problems/main/bad-response",
            "Main dependency returned an unexpected status",
            HttpStatus.BAD_GATEWAY,
            ProblemCategory.MAIN_DEPENDENCY,
            false,
            "The main dependency returned an unexpected response status."),
    MAIN_INVALID_PAYLOAD(
            "MAIN-INVALID-PAYLOAD",
            "/problems/main/invalid-payload",
            "Main dependency returned an invalid payload",
            HttpStatus.BAD_GATEWAY,
            ProblemCategory.MAIN_DEPENDENCY,
            false,
            "The main dependency returned a payload that could not be read."),
    MAIN_CONTRACT_VIOLATION(
            "MAIN-CONTRACT-VIOLATION",
            "/problems/main/contract-violation",
            "Main dependency payload violates contract",
            HttpStatus.BAD_GATEWAY,
            ProblemCategory.MAIN_DEPENDENCY,
            false,
            "The main dependency payload does not satisfy the required contract."),
    MAIN_AUTH_FAILED(
            "MAIN-AUTH-FAILED",
            "/problems/main/auth-failed",
            "Main dependency refused authentication",
            HttpStatus.BAD_GATEWAY,
            ProblemCategory.MAIN_DEPENDENCY,
            false,
            "The service could not authenticate to the main dependency."),
    ENRICH_TIMEOUT(
            "ENRICH-TIMEOUT",
            "/problems/enrichment/timeout",
            "Enrichment dependency timed out",
            HttpStatus.GATEWAY_TIMEOUT,
            ProblemCategory.ENRICHMENT_DEPENDENCY,
            true,
            "A required enrichment call did not complete within the allowed time."),
    ENRICH_UNAVAILABLE(
            "ENRICH-UNAVAILABLE",
            "/problems/enrichment/unavailable",
            "Enrichment dependency unavailable",
            HttpStatus.GATEWAY_TIMEOUT,
            ProblemCategory.ENRICHMENT_DEPENDENCY,
            true,
            "A required enrichment dependency is currently unavailable."),
    ENRICH_BAD_RESPONSE(
            "ENRICH-BAD-RESPONSE",
            "/problems/enrichment/bad-response",
            "Enrichment dependency returned an unexpected status",
            HttpStatus.BAD_GATEWAY,
            ProblemCategory.ENRICHMENT_DEPENDENCY,
            false,
            "A required enrichment dependency returned an unexpected response status."),
    ENRICH_INVALID_PAYLOAD(
            "ENRICH-INVALID-PAYLOAD",
            "/problems/enrichment/invalid-payload",
            "Enrichment dependency returned an invalid payload",
            HttpStatus.BAD_GATEWAY,
            ProblemCategory.ENRICHMENT_DEPENDENCY,
            false,
            "A required enrichment dependency returned a payload that could not be read."),
    ENRICH_CONTRACT_VIOLATION(
            "ENRICH-CONTRACT-VIOLATION",
            "/problems/enrichment/contract-violation",
            "Enrichment dependency payload violates contract",
            HttpStatus.BAD_GATEWAY,
            ProblemCategory.ENRICHMENT_DEPENDENCY,
            false,
            "A required enrichment dependency payload does not satisfy the required contract."),
    ENRICH_AUTH_FAILED(
            "ENRICH-AUTH-FAILED",
            "/problems/enrichment/auth-failed",
            "Enrichment dependency refused authentication",
            HttpStatus.BAD_GATEWAY,
            ProblemCategory.ENRICHMENT_DEPENDENCY,
            false,
            "The service could not authenticate to a required enrichment dependency."),
    ORCH_MERGE_FAILED(
            "ORCH-MERGE-FAILED",
            "/problems/orchestration/merge-failed",
            "Aggregation failed",
            HttpStatus.INTERNAL_SERVER_ERROR,
            ProblemCategory.ORCHESTRATION,
            false,
            "The service could not assemble the aggregated response."),
    ORCH_MAPPING_FAILED(
            "ORCH-MAPPING-FAILED",
            "/problems/orchestration/mapping-failed",
            "Response mapping failed",
            HttpStatus.INTERNAL_SERVER_ERROR,
            ProblemCategory.ORCHESTRATION,
            false,
            "The service could not map the aggregated response."),
    ORCH_INVARIANT_VIOLATED(
            "ORCH-INVARIANT-VIOLATED",
            "/problems/orchestration/invariant",
            "Internal invariant violated",
            HttpStatus.INTERNAL_SERVER_ERROR,
            ProblemCategory.ORCHESTRATION,
            false,
            "The service detected an internal aggregation invariant violation."),
    ORCH_CONFIG_INVALID(
            "ORCH-CONFIG-INVALID",
            "/problems/orchestration/config",
            "Internal configuration error",
            HttpStatus.INTERNAL_SERVER_ERROR,
            ProblemCategory.ORCHESTRATION,
            false,
            "The service configuration is invalid for this request."),
    PLATFORM_INTERNAL(
            "PLATFORM-INTERNAL",
            "/problems/platform/internal",
            "Internal server error",
            HttpStatus.INTERNAL_SERVER_ERROR,
            ProblemCategory.PLATFORM,
            false,
            "The service encountered an unexpected internal error."),
    PLATFORM_OVERLOADED(
            "PLATFORM-OVERLOADED",
            "/problems/platform/overloaded",
            "Service overloaded",
            HttpStatus.SERVICE_UNAVAILABLE,
            ProblemCategory.PLATFORM,
            true,
            "The service is temporarily overloaded.");

    private final String errorCode;
    private final URI type;
    private final String title;
    private final HttpStatus status;
    private final ProblemCategory category;
    private final boolean retryable;
    private final String defaultDetail;

    ProblemCatalog(
            String errorCode,
            String type,
            String title,
            HttpStatus status,
            ProblemCategory category,
            boolean retryable,
            String defaultDetail) {
        this.errorCode = errorCode;
        this.type = URI.create(type);
        this.title = title;
        this.status = status;
        this.category = category;
        this.retryable = retryable;
        this.defaultDetail = defaultDetail;
    }

    public String errorCode() {
        return errorCode;
    }

    public URI type() {
        return type;
    }

    public String title() {
        return title;
    }

    public HttpStatus status() {
        return status;
    }

    public ProblemCategory category() {
        return category;
    }

    public boolean retryable() {
        return retryable;
    }

    public String defaultDetail() {
        return defaultDetail;
    }
}
