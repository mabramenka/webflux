package dev.abramenka.aggregation.workflow.binding;

import java.util.List;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

/**
 * One REST dependency call inside a binding. Receives the deduplicated keys extracted by the
 * binding's {@link KeyExtractionRule} and returns the raw downstream JSON response.
 */
@FunctionalInterface
public interface DownstreamCall {

    Mono<JsonNode> fetch(List<String> keys);
}
