package com.example.aggregation.client.impl;

import com.example.aggregation.client.MainClient;
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
public class WebClientMainClient implements MainClient {

    @Qualifier("mainWebClient")
    private final WebClient webClient;

    @Override
    public Mono<JsonNode> postMain(ObjectNode request, DownstreamHeaders headers) {
        return webClient.post()
            .uri("/main")
            .contentType(MediaType.APPLICATION_JSON)
            .headers(headers::applyTo)
            .bodyValue(request)
            .retrieve()
            .onStatus(status -> status.isError(), response ->
                response.bodyToMono(String.class)
                    .defaultIfEmpty("main downstream request failed")
                    .map(message -> new IllegalStateException("Main downstream failed: " + message))
            )
            .bodyToMono(JsonNode.class);
    }
}
