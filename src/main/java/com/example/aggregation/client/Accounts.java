package com.example.aggregation.client;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

public interface Accounts {

    @PostExchange(
        value = "/accounts",
        contentType = MediaType.APPLICATION_JSON_VALUE,
        accept = MediaType.APPLICATION_JSON_VALUE
    )
    Mono<JsonNode> fetchAccounts(
        @RequestBody ObjectNode request,
        ClientRequestContext clientRequestContext
    );
}
