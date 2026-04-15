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

    private static final String CURRENCY_FIELD = "currency";

    private static final EnrichmentRule ENRICHMENT_RULE = EnrichmentRule.builder()
        .mainItems("$.data[*]", "accounts[*].id")
        .responseItems("$.data[*]", "id")
        .requestKeysField("ids")
        .targetField("account1")
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
            CURRENCY_FIELD,
            context.mainResponse().path(CURRENCY_FIELD)
                .asString(context.inboundRequest().path(CURRENCY_FIELD).asString("USD"))
        );

        return pricingClient.postPricing(request, context.downstreamRequest());
    }
}
