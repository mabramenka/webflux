package com.example.aggregation.client;

import com.example.aggregation.web.DownstreamRequest;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

public interface PricingClient {

    Mono<JsonNode> postPricing(ObjectNode request, DownstreamRequest downstreamRequest);
}
