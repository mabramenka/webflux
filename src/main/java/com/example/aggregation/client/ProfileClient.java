package com.example.aggregation.client;

import com.example.aggregation.web.DownstreamHeaders;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

public interface ProfileClient {

    Mono<JsonNode> postProfile(ObjectNode request, DownstreamHeaders headers);
}
