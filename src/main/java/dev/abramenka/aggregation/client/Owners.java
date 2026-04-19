package dev.abramenka.aggregation.client;

import dev.abramenka.aggregation.model.ClientRequestContext;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

public interface Owners {

    @PostExchange(
            value = "/owners",
            contentType = MediaType.APPLICATION_JSON_VALUE,
            accept = MediaType.APPLICATION_JSON_VALUE)
    Mono<JsonNode> fetchOwners(@RequestBody ObjectNode request, ClientRequestContext clientRequestContext);
}
