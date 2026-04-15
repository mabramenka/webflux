package com.example.aggregation.application.enrichment;

import com.example.aggregation.application.AggregationContext;
import com.example.aggregation.downstream.WebClientProfileClient;
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
public class ProfileEnrichment implements AggregationEnrichment {

    private static final String CUSTOMER_ID_FIELD = "customerId";
    private static final String CUSTOMER_PROFILE_FIELD = "customerProfile";

    private final WebClientProfileClient profileClient;

    @Override
    public String name() {
        return "profile";
    }

    @Override
    public boolean supports(AggregationContext context) {
        return !context.mainResponse().path(CUSTOMER_ID_FIELD).asString("").isBlank();
    }

    @Override
    public Mono<JsonNode> fetch(AggregationContext context) {
        ObjectNode request = JsonNodeFactory.instance.objectNode();
        request.put(CUSTOMER_ID_FIELD, context.mainResponse().path(CUSTOMER_ID_FIELD).asString());
        request.put("market", context.inboundRequest().path("market").asString("US"));
        return profileClient.postProfile(request, context.downstreamRequest());
    }

    @Override
    public void merge(ObjectNode root, JsonNode enrichmentResponse) {
        root.set(CUSTOMER_PROFILE_FIELD, enrichmentResponse);
    }
}
