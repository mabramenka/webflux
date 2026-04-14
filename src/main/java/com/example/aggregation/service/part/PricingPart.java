package com.example.aggregation.service.part;

import com.example.aggregation.client.PricingClient;
import com.example.aggregation.service.AggregationContext;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

@Component
@Order(200)
@RequiredArgsConstructor
public class PricingPart extends KeyedArrayEnrichmentPart {

    private final PricingClient pricingClient;

    @Override
    public String name() {
        return "pricing";
    }

    @Override
    public Mono<JsonNode> fetch(AggregationContext context) {
        ObjectNode request = requestWithKeys(context.mainResponse());
        request.put(
            "currency",
            context.mainResponse().path("currency")
                .asString(context.inboundRequest().path("currency").asString("USD"))
        );

        return pricingClient.postPricing(request, context.downstreamRequest());
    }

    @Override
    protected String requestKeysField() {
        return "itemIds";
    }

    @Override
    protected List<EnrichmentTarget> targetsFrom(JsonNode root) {
        return targetsFromArray(root, "items", item -> item.path("itemId").asString(""));
    }

    @Override
    protected String responseEntriesField() {
        return "prices";
    }

    @Override
    protected String responseKeyField() {
        return "itemId";
    }

    @Override
    protected String targetEnrichmentField() {
        return "pricing";
    }
}
