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
public class WebClientAccountGroupClient {

    private final WebClient webClient;

    public WebClientAccountGroupClient(@Qualifier("accountGroupWebClient") WebClient webClient) {
        this.webClient = webClient;
    }

    public Mono<JsonNode> postAccountGroup(ObjectNode request, ClientRequestContext clientRequestContext) {
        return webClient.post()
            .uri(uriBuilder -> clientRequestContext.applyQueryParams(uriBuilder.path("/account-groups")).build())
            .contentType(MediaType.APPLICATION_JSON)
            .headers(clientRequestContext.headers()::applyTo)
            .bodyValue(request)
            .retrieve()
            .onStatus(HttpStatusCode::isError, response ->
                response.bodyToMono(String.class)
                    .defaultIfEmpty("account group client request failed")
                    .map(message -> new IllegalStateException("Account group client failed: " + message))
            )
            .bodyToMono(JsonNode.class);
    }
}
