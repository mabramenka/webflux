package dev.abramenka.aggregation.model;

import dev.abramenka.aggregation.error.DownstreamClientException;
import dev.abramenka.aggregation.error.FacadeException;
import dev.abramenka.aggregation.error.OrchestrationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

public interface AggregationEnrichment extends AggregationPart {

    Mono<JsonNode> fetch(AggregationContext context);

    void merge(ObjectNode root, JsonNode enrichmentResponse);

    @Override
    default Mono<AggregationPartResult> execute(ObjectNode rootSnapshot, AggregationContext context) {
        return fetch(context)
                .map(response -> mergeIntoSnapshot(rootSnapshot, response))
                .switchIfEmpty(
                        Mono.fromSupplier(() -> AggregationPartResult.empty(name(), PartSkipReason.DOWNSTREAM_EMPTY)))
                .onErrorResume(
                        DownstreamClientException.class,
                        ex -> isNotFound(ex)
                                ? Mono.just(AggregationPartResult.empty(name(), PartSkipReason.DOWNSTREAM_NOT_FOUND))
                                : Mono.error(ex));
    }

    private AggregationPartResult mergeIntoSnapshot(ObjectNode rootSnapshot, JsonNode response) {
        ObjectNode workingRoot = rootSnapshot.deepCopy();
        try {
            merge(workingRoot, response);
        } catch (FacadeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw OrchestrationException.mergeFailed(ex);
        }
        return AggregationPartResult.patch(name(), rootSnapshot, workingRoot);
    }

    private static boolean isNotFound(DownstreamClientException ex) {
        HttpStatusCode status = ex.downstreamStatusCode();
        return status != null && status.value() == HttpStatus.NOT_FOUND.value();
    }
}
