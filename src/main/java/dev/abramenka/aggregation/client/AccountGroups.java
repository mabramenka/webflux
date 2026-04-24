package dev.abramenka.aggregation.client;

import dev.abramenka.aggregation.model.ClientRequestContext;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.PostExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

public interface AccountGroups {

    String DEFAULT_FIELDS = "id,status,name,accounts,owners1";

    @PostExchange(
            value = "/account-groups",
            contentType = MediaType.APPLICATION_JSON_VALUE,
            accept = MediaType.APPLICATION_JSON_VALUE)
    Mono<JsonNode> fetchAccountGroup(
            @RequestBody ObjectNode request,
            @RequestParam(ClientRequestContext.FIELDS_QUERY_PARAM) String fields,
            ClientRequestContext clientRequestContext);
}
