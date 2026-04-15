package com.example.aggregation.downstream;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

@Component
public class WebClientMainClient {

    private final WebClient webClient;

    public WebClientMainClient(@Qualifier("mainWebClient") WebClient webClient) {
        this.webClient = webClient;
    }

    public Mono<JsonNode> postMain(ObjectNode request, DownstreamRequest downstreamRequest) {
        return webClient.post()
            .uri(uriBuilder -> downstreamRequest.applyQueryParams(uriBuilder.path("/main")).build())
            .contentType(MediaType.APPLICATION_JSON)
            .headers(downstreamRequest.headers()::applyTo)
            .bodyValue(request)
            .retrieve()
            .onStatus(HttpStatusCode::isError, response ->
                response.bodyToMono(String.class)
                    .defaultIfEmpty("main downstream request failed")
                    .map(message -> new IllegalStateException("Main downstream failed: " + message))
            )
            .bodyToMono(JsonNode.class);
    }
}
