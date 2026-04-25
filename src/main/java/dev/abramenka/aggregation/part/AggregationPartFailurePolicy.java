package dev.abramenka.aggregation.part;

import dev.abramenka.aggregation.error.DownstreamClientException;
import dev.abramenka.aggregation.error.FacadeException;
import dev.abramenka.aggregation.error.OrchestrationException;
import dev.abramenka.aggregation.error.ProblemCatalog;
import dev.abramenka.aggregation.model.AggregationPart;
import dev.abramenka.aggregation.model.PartCriticality;
import dev.abramenka.aggregation.model.PartOutcomeReason;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Component
@Slf4j
class AggregationPartFailurePolicy {

    FailureDecision decide(AggregationPart part, Throwable error) {
        PartCriticality criticality = part.criticality();
        if (criticality == PartCriticality.OPTIONAL && error instanceof DownstreamClientException downstream) {
            ProblemCatalog catalog = downstream.catalog();
            PartOutcomeReason reason = toFailureReason(catalog);
            log.info(
                    "Optional aggregation part '{}' failed with {} ({}); continuing request",
                    part.name(),
                    catalog.errorCode(),
                    reason);
            return FailureDecision.continueWith(reason, catalog.errorCode());
        }
        Throwable resolved = error instanceof FacadeException ? error : OrchestrationException.invariantViolated(error);
        enrichProblemDetail(part, resolved, criticality);
        return FailureDecision.fail(resolved);
    }

    private static PartOutcomeReason toFailureReason(ProblemCatalog catalog) {
        return switch (catalog) {
            case ENRICH_TIMEOUT -> PartOutcomeReason.TIMEOUT;
            case ENRICH_INVALID_PAYLOAD -> PartOutcomeReason.INVALID_PAYLOAD;
            case ENRICH_BAD_RESPONSE -> PartOutcomeReason.BAD_RESPONSE;
            case ENRICH_UNAVAILABLE -> PartOutcomeReason.UNAVAILABLE;
            case ENRICH_AUTH_FAILED -> PartOutcomeReason.AUTH_FAILED;
            case ENRICH_CONTRACT_VIOLATION -> PartOutcomeReason.CONTRACT_VIOLATION;
            default -> PartOutcomeReason.INTERNAL;
        };
    }

    private static void enrichProblemDetail(AggregationPart part, Throwable error, PartCriticality criticality) {
        if (error instanceof FacadeException facadeException) {
            facadeException.getBody().setProperty("part", part.name());
            facadeException.getBody().setProperty("criticality", criticality.name());
        }
    }

    record FailureDecision(
            boolean failRequest,
            @Nullable Throwable error,
            @Nullable PartOutcomeReason reason,
            @Nullable String errorCode) {

        static FailureDecision fail(Throwable error) {
            return new FailureDecision(true, error, null, null);
        }

        static FailureDecision continueWith(PartOutcomeReason reason, String errorCode) {
            return new FailureDecision(false, null, reason, errorCode);
        }
    }
}
