package com.example.aggregation.service.part;

import com.example.aggregation.client.PricingClient;
import com.example.aggregation.service.AggregationContext;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

@Component
@Order(200)
public class PricingPart extends KeyedArrayEnrichmentPart {

    private static final Rule ENRICHMENT_RULE = keyedArrayRule()
        .targetRule(mainNestedArrayToSiblingArrayRule("data", "accounts", account -> account.path("id").asString(""))
            .requestKeysField("itemIds")
            .build())
        .responseRule(responseArrayRule("data", account -> account.path("id").asString(""))
            .targetField("account1")
            .build())
        .build();

    private final PricingClient pricingClient;

    public PricingPart(PricingClient pricingClient) {
        super(ENRICHMENT_RULE);
        this.pricingClient = pricingClient;
    }

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
}
