package com.example.aggregation.client.impl;

import com.example.aggregation.client.ProfileClient;
import com.example.aggregation.web.DownstreamHeaders;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

@Component
@RequiredArgsConstructor
public class WebClientProfileClient implements ProfileClient {

    @Qualifier("profileWebClient")
    private final WebClient webClient;

    @Override
    public Mono<JsonNode> postProfile(ObjectNode request, DownstreamHeaders headers) {
        return webClient.post()
            .uri("/profiles")
            .contentType(MediaType.APPLICATION_JSON)
            .headers(headers::applyTo)
            .bodyValue(request)
            .retrieve()
            .onStatus(status -> status.isError(), response ->
                response.bodyToMono(String.class)
                    .defaultIfEmpty("profile downstream request failed")
                    .map(message -> new IllegalStateException("Profile downstream failed: " + message))
            )
            .bodyToMono(JsonNode.class);
    }
}
