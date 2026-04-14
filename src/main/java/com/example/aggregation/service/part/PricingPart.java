package com.example.aggregation.service.part;

import com.example.aggregation.client.PricingClient;
import com.example.aggregation.service.AggregationContext;
import com.example.aggregation.service.AggregationPart;
import java.util.HashMap;
import java.util.List;
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
        return !itemTargets(context.mainResponse()).isEmpty();
    }

    @Override
    public Mono<JsonNode> fetch(AggregationContext context) {
        List<ItemTarget> itemTargets = itemTargets(context.mainResponse());

        ObjectNode request = JsonNodeFactory.instance.objectNode();
        request.set("itemIds", itemIdArray(itemTargets));
        request.put(
            "currency",
            context.mainResponse().path("currency")
                .asString(context.inboundRequest().path("currency").asString("USD"))
        );

        return pricingClient.postPricing(request, context.downstreamRequest());
    }

    @Override
    public void merge(ObjectNode root, JsonNode pricingResponse) {
        Map<String, JsonNode> pricingByItemId = pricingByItemId(pricingResponse);
        itemTargets(root).forEach(target -> attachPricing(target, pricingByItemId));
    }

    private List<ItemTarget> itemTargets(JsonNode root) {
        JsonNode itemsNode = root.path("items");
        if (!itemsNode.isArray()) {
            return List.of();
        }

        return itemsNode.values().stream()
            .filter(ObjectNode.class::isInstance)
            .map(ObjectNode.class::cast)
            .map(item -> new ItemTarget(item.path("itemId").asString(""), item))
            .filter(target -> !target.itemId().isBlank())
            .toList();
    }

    private ArrayNode itemIdArray(List<ItemTarget> itemTargets) {
        ArrayNode itemIds = JsonNodeFactory.instance.arrayNode();
        itemTargets.stream()
            .map(ItemTarget::itemId)
            .forEach(itemIds::add);
        return itemIds;
    }

    private Map<String, JsonNode> pricingByItemId(JsonNode pricingResponse) {
        Map<String, JsonNode> pricingByItemId = new HashMap<>();
        pricingResponse.path("prices").forEach(price -> {
            String itemId = price.path("itemId").asString("");
            if (!itemId.isBlank()) {
                pricingByItemId.put(itemId, price);
            }
        });
        return pricingByItemId;
    }

    private void attachPricing(ItemTarget target, Map<String, JsonNode> pricingByItemId) {
        JsonNode pricing = pricingByItemId.get(target.itemId());
        if (pricing != null) {
            target.item().set("pricing", pricing);
        }
    }

    private record ItemTarget(String itemId, ObjectNode item) {
    }
}
