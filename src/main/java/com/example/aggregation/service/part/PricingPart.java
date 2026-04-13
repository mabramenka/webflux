package com.example.aggregation.service.part;

import com.example.aggregation.client.PricingClient;
import com.example.aggregation.service.AggregationContext;
import com.example.aggregation.service.AggregationPart;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

@Component
@Order(200)
@RequiredArgsConstructor
public class PricingPart implements AggregationPart {

    private final PricingClient pricingClient;

    @Override
    public String name() {
        return "pricing";
    }

    @Override
    public boolean supports(AggregationContext context) {
        ArrayNode itemIds = itemIds(context);
        return !itemIds.isEmpty();
    }

    @Override
    public Mono<JsonNode> fetch(AggregationContext context) {
        ObjectNode request = JsonNodeFactory.instance.objectNode();
        request.set("itemIds", itemIds(context));
        request.put(
            "currency",
            context.mainResponse().path("currency")
                .asString(context.inboundRequest().path("currency").asString("USD"))
        );

        return pricingClient.postPricing(request, context.headers());
    }

    @Override
    public void merge(ObjectNode root, JsonNode pricingResponse) {
        Map<String, BigDecimal> amountByItemId = new HashMap<>();
        pricingResponse.path("prices").forEach(price -> {
            String itemId = price.path("itemId").asString("");
            price.path("amount").asDecimalOpt()
                .filter(amount -> !itemId.isBlank())
                .ifPresent(amount -> amountByItemId.put(itemId, amount));
        });

        root.withArrayProperty("items").forEach(item -> {
            if (item instanceof ObjectNode itemObject) {
                String itemId = itemObject.path("itemId").asString("");
                BigDecimal amount = amountByItemId.get(itemId);
                if (amount != null) {
                    itemObject.put("price", amount);
                }
            }
        });
    }

    private ArrayNode itemIds(AggregationContext context) {
        ArrayNode itemIds = JsonNodeFactory.instance.arrayNode();
        JsonNode itemsNode = context.mainResponse().path("items");
        if (!itemsNode.isArray()) {
            return itemIds;
        }

        itemsNode.forEach(item -> {
            String itemId = item.path("itemId").asString("");
            if (!itemId.isBlank()) {
                itemIds.add(itemId);
            }
        });
        return itemIds;
    }
}
