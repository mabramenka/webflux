package dev.abramenka.aggregation.client;

import dev.abramenka.aggregation.model.ClientRequestContext;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.PostExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

public interface Accounts {

    String DEFAULT_FIELDS = "id,number,status,balance";

    @PostExchange(
            value = "/accounts",
            contentType = MediaType.APPLICATION_JSON_VALUE,
            accept = MediaType.APPLICATION_JSON_VALUE)
    Mono<JsonNode> fetchAccounts(
            @RequestBody ObjectNode request,
            @RequestParam(ClientRequestContext.FIELDS_QUERY_PARAM) String fields,
            ClientRequestContext clientRequestContext);
}
