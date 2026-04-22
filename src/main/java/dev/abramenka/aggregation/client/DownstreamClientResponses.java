package dev.abramenka.aggregation.client;

import dev.abramenka.aggregation.error.DownstreamClientException;
import dev.abramenka.aggregation.error.FacadeException;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

public final class DownstreamClientResponses {

    private DownstreamClientResponses() {}

    public static Mono<JsonNode> requireBody(String clientName, Mono<JsonNode> response) {
        return response.switchIfEmpty(Mono.error(() -> DownstreamClientException.contractViolation(clientName)))
                .onErrorMap(
                        ex -> !(ex instanceof FacadeException),
                        ex -> DownstreamClientException.transport(clientName, ex));
    }
}
