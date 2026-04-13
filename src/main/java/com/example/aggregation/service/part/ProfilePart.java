package com.example.aggregation.service.part;

import com.example.aggregation.client.ProfileClient;
import com.example.aggregation.service.AggregationContext;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

@Component
@Order(100)
@RequiredArgsConstructor
public class ProfilePart extends EmbeddedJsonPart {

    private final ProfileClient profileClient;

    @Override
    public String name() {
        return "profile";
    }

    @Override
    protected String targetField() {
        return "customerProfile";
    }

    @Override
    public boolean supports(AggregationContext context) {
        return !context.mainResponse().path("customerId").asString("").isBlank();
    }

    @Override
    public Mono<JsonNode> fetch(AggregationContext context) {
        ObjectNode request = JsonNodeFactory.instance.objectNode();
        request.put("customerId", context.mainResponse().path("customerId").asString());
        request.put("market", context.inboundRequest().path("market").asString("US"));
        return profileClient.postProfile(request, context.headers());
    }
}
