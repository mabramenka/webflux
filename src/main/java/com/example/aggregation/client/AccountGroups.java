package com.example.aggregation.client;

import com.example.aggregation.model.ClientRequestContext;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

public interface AccountGroups {

    @PostExchange(
            value = "/account-groups",
            contentType = MediaType.APPLICATION_JSON_VALUE,
            accept = MediaType.APPLICATION_JSON_VALUE)
    Mono<JsonNode> fetchAccountGroup(@RequestBody ObjectNode request, ClientRequestContext clientRequestContext);
}
