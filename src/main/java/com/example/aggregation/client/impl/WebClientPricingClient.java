package com.example.aggregation.client.impl;

import com.example.aggregation.client.PricingClient;
import com.example.aggregation.web.DownstreamRequest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

@Component
public class WebClientPricingClient implements PricingClient {

    private final WebClient webClient;

    public WebClientPricingClient(@Qualifier("pricingWebClient") WebClient webClient) {
        this.webClient = webClient;
    }

    @Override
    public Mono<JsonNode> postPricing(ObjectNode request, DownstreamRequest downstreamRequest) {
        return webClient.post()
            .uri(uriBuilder -> downstreamRequest.applyQueryParams(uriBuilder.path("/pricing")).build())
            .contentType(MediaType.APPLICATION_JSON)
            .headers(downstreamRequest.headers()::applyTo)
            .bodyValue(request)
            .retrieve()
            .onStatus(HttpStatusCode::isError, response ->
                response.bodyToMono(String.class)
                    .defaultIfEmpty("pricing downstream request failed")
                    .map(message -> new IllegalStateException("Pricing downstream failed: " + message))
            )
            .bodyToMono(JsonNode.class);
    }
}
