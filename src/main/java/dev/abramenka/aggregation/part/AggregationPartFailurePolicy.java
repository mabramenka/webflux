package dev.abramenka.aggregation.part;

import dev.abramenka.aggregation.error.FacadeException;
import dev.abramenka.aggregation.error.OrchestrationException;
import dev.abramenka.aggregation.error.ProblemCatalog;
import dev.abramenka.aggregation.model.AggregationPart;
import dev.abramenka.aggregation.model.PartCriticality;
import dev.abramenka.aggregation.model.PartFailureReason;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Component
@Slf4j
class AggregationPartFailurePolicy {

    FailureDecision decide(AggregationPart part, Throwable error) {
        Throwable resolved = error instanceof FacadeException ? error : OrchestrationException.invariantViolated(error);
        ProblemCatalog catalog =
                resolved instanceof FacadeException facade ? facade.catalog() : ProblemCatalog.ORCH_INVARIANT_VIOLATED;
        PartFailureReason reason = toFailureReason(catalog);
        PartCriticality criticality = part.criticality();
        if (criticality == PartCriticality.REQUIRED) {
            enrichProblemDetail(part, resolved, reason, criticality);
            return FailureDecision.fail(resolved);
        }
        log.info(
                "Optional aggregation part '{}' failed with {} ({}); continuing request",
                part.name(),
                catalog.errorCode(),
                reason);
        return FailureDecision.continueWith(reason, catalog.errorCode());
    }

    private static PartFailureReason toFailureReason(ProblemCatalog catalog) {
        return switch (catalog) {
            case MAIN_TIMEOUT, ENRICH_TIMEOUT -> PartFailureReason.TIMEOUT;
            case MAIN_INVALID_PAYLOAD, ENRICH_INVALID_PAYLOAD -> PartFailureReason.INVALID_PAYLOAD;
            case MAIN_BAD_RESPONSE, ENRICH_BAD_RESPONSE -> PartFailureReason.BAD_RESPONSE;
            case MAIN_UNAVAILABLE, ENRICH_UNAVAILABLE -> PartFailureReason.UNAVAILABLE;
            case MAIN_AUTH_FAILED, ENRICH_AUTH_FAILED -> PartFailureReason.AUTH_FAILED;
            case MAIN_CONTRACT_VIOLATION, ENRICH_CONTRACT_VIOLATION -> PartFailureReason.CONTRACT_VIOLATION;
            default -> PartFailureReason.INTERNAL;
        };
    }

    private static void enrichProblemDetail(
            AggregationPart part, Throwable error, PartFailureReason reason, PartCriticality criticality) {
        if (error instanceof FacadeException facadeException) {
            facadeException.getBody().setProperty("part", part.name());
            facadeException.getBody().setProperty("criticality", criticality.name());
            facadeException.getBody().setProperty("partReason", reason.name());
        }
    }

    record FailureDecision(
            boolean failRequest,
            @Nullable Throwable error,
            @Nullable PartFailureReason reason,
            @Nullable String errorCode) {

        static FailureDecision fail(Throwable error) {
            return new FailureDecision(true, error, null, null);
        }

        static FailureDecision continueWith(PartFailureReason reason, String errorCode) {
            return new FailureDecision(false, null, reason, errorCode);
        }
    }
}
