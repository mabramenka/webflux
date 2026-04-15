package com.example.aggregation.client;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

@Component
public class WebClientAccountClient {

    private final WebClient webClient;

    public WebClientAccountClient(@Qualifier("accountWebClient") WebClient webClient) {
        this.webClient = webClient;
    }

    public Mono<JsonNode> postAccount(ObjectNode request, ClientRequestContext clientRequestContext) {
        return webClient.post()
            .uri(uriBuilder -> clientRequestContext.applyQueryParams(uriBuilder.path("/accounts")).build())
            .contentType(MediaType.APPLICATION_JSON)
            .headers(clientRequestContext.headers()::applyTo)
            .bodyValue(request)
            .retrieve()
            .onStatus(HttpStatusCode::isError, response ->
                response.bodyToMono(String.class)
                    .defaultIfEmpty("account client request failed")
                    .map(message -> new IllegalStateException("Account client failed: " + message))
            )
            .bodyToMono(JsonNode.class);
    }
}
