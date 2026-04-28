package dev.abramenka.aggregation.error;

import org.jspecify.annotations.Nullable;

public final class OrchestrationException extends FacadeException {

    private OrchestrationException(ProblemCatalog catalog, @Nullable Throwable cause) {
        super(catalog, null, cause);
    }

    public static OrchestrationException mergeFailed(Throwable cause) {
        return new OrchestrationException(ProblemCatalog.ORCH_MERGE_FAILED, cause);
    }

    public static OrchestrationException invariantViolated(Throwable cause) {
        return new OrchestrationException(ProblemCatalog.ORCH_INVARIANT_VIOLATED, cause);
    }

    public static OrchestrationException configInvalid(Throwable cause) {
        return new OrchestrationException(ProblemCatalog.ORCH_CONFIG_INVALID, cause);
    }
}
