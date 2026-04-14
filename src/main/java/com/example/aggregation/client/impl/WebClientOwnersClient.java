package com.example.aggregation.client.impl;

import com.example.aggregation.client.OwnersClient;
import com.example.aggregation.web.DownstreamRequest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

@Component
public class WebClientOwnersClient implements OwnersClient {

    private final WebClient webClient;

    public WebClientOwnersClient(@Qualifier("ownersWebClient") WebClient webClient) {
        this.webClient = webClient;
    }

    @Override
    public Mono<JsonNode> postOwners(ObjectNode request, DownstreamRequest downstreamRequest) {
        return webClient.post()
            .uri(uriBuilder -> downstreamRequest.applyQueryParams(uriBuilder.path("/owners")).build())
            .contentType(MediaType.APPLICATION_JSON)
            .headers(downstreamRequest.headers()::applyTo)
            .bodyValue(request)
            .retrieve()
            .onStatus(status -> status.isError(), response ->
                response.bodyToMono(String.class)
                    .defaultIfEmpty("owners downstream request failed")
                    .map(message -> new IllegalStateException("Owners downstream failed: " + message))
            )
            .bodyToMono(JsonNode.class);
    }
}
