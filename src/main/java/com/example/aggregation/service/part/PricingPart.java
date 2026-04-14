package com.example.aggregation.service.part;

import com.example.aggregation.client.PricingClient;
import com.example.aggregation.service.AggregationContext;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

@Component
@Order(200)
@RequiredArgsConstructor
public class PricingPart extends MatchedArrayEnrichmentPart {

    private final PricingClient pricingClient;

    @Override
    public String name() {
        return "pricing";
    }

    @Override
    public Mono<JsonNode> fetch(AggregationContext context) {
        ObjectNode request = requestWithTargetIds(context.mainResponse());
        request.put(
            "currency",
            context.mainResponse().path("currency")
                .asString(context.inboundRequest().path("currency").asString("USD"))
        );

        return pricingClient.postPricing(request, context.downstreamRequest());
    }

    @Override
    protected String targetIdsRequestField() {
        return "itemIds";
    }

    @Override
    protected String sourceArrayField() {
        return "items";
    }

    @Override
    protected String sourceIdField() {
        return "itemId";
    }

    @Override
    protected String responseArrayField() {
        return "prices";
    }

    @Override
    protected String responseIdField() {
        return "itemId";
    }

    @Override
    protected String targetField() {
        return "pricing";
    }
}
