package com.example.aggregation.service;

import com.example.aggregation.client.MainClient;
import com.example.aggregation.client.PricingClient;
import com.example.aggregation.client.ProfileClient;
import com.example.aggregation.web.DownstreamHeaders;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.MissingNode;
import tools.jackson.databind.node.ObjectNode;

@Service
@RequiredArgsConstructor
public class AggregateService {

    private static final MissingNode MISSING = MissingNode.getInstance();

    private final MainClient mainClient;
    private final ProfileClient profileClient;
    private final PricingClient pricingClient;
    private final ObjectMapper objectMapper;

    public Mono<JsonNode> aggregate(ObjectNode inboundRequest, DownstreamHeaders headers) {
        ObjectNode mainRequest = buildMainRequest(inboundRequest);

        return mainClient.postMain(mainRequest, headers)
            .flatMap(mainResponse -> {
                Mono<JsonNode> profileMono = fetchOptionalProfile(mainResponse, inboundRequest, headers);
                Mono<JsonNode> pricingMono = fetchOptionalPricing(mainResponse, inboundRequest, headers);

                return Mono.zip(profileMono, pricingMono)
                    .map(results -> merge(mainResponse, results.getT1(), results.getT2()));
            });
    }

    private ObjectNode buildMainRequest(ObjectNode inboundRequest) {
        ObjectNode request = objectMapper.createObjectNode();
        request.put("customerId", inboundRequest.path("customerId").asText());
        request.put("market", inboundRequest.path("market").asText("US"));
        request.put("includeItems", inboundRequest.path("includeItems").asBoolean(true));
        return request;
    }

    private Mono<JsonNode> fetchOptionalProfile(JsonNode mainResponse, ObjectNode inbound, DownstreamHeaders headers) {
        String customerId = mainResponse.path("customerId").asText("");
        boolean includeProfile = inbound.path("includeProfile").asBoolean(true);

        if (!includeProfile || customerId.isBlank()) {
            return Mono.just(MISSING);
        }

        ObjectNode request = objectMapper.createObjectNode();
        request.put("customerId", customerId);
        request.put("market", inbound.path("market").asText("US"));

        return profileClient.postProfile(request, headers)
            .onErrorResume(ex -> Mono.just(MISSING));
    }

    private Mono<JsonNode> fetchOptionalPricing(JsonNode mainResponse, ObjectNode inbound, DownstreamHeaders headers) {
        JsonNode itemsNode = mainResponse.path("items");
        if (!itemsNode.isArray() || itemsNode.isEmpty()) {
            return Mono.just(MISSING);
        }

        ObjectNode request = objectMapper.createObjectNode();
        ArrayNode itemIds = objectMapper.createArrayNode();
        itemsNode.forEach(item -> {
            String itemId = item.path("itemId").asText("");
            if (!itemId.isBlank()) {
                itemIds.add(itemId);
            }
        });

        if (itemIds.isEmpty()) {
            return Mono.just(MISSING);
        }

        request.set("itemIds", itemIds);
        request.put("currency", mainResponse.path("currency").asText(inbound.path("currency").asText("USD")));

        return pricingClient.postPricing(request, headers)
            .onErrorResume(ex -> Mono.just(MISSING));
    }

    private JsonNode merge(JsonNode mainResponse, JsonNode profileResponse, JsonNode pricingResponse) {
        ObjectNode root = (ObjectNode) mainResponse.deepCopy();

        if (!profileResponse.isMissingNode()) {
            root.set("customerProfile", profileResponse);
        }

        if (!pricingResponse.isMissingNode()) {
            mergePricingIntoItems(root, pricingResponse);
        }

        return root;
    }

    private void mergePricingIntoItems(ObjectNode root, JsonNode pricingResponse) {
        Map<String, BigDecimal> amountByItemId = new HashMap<>();
        pricingResponse.path("prices").forEach(price -> {
            String itemId = price.path("itemId").asText("");
            if (!itemId.isBlank()) {
                amountByItemId.put(itemId, price.path("amount").decimalValue());
            }
        });

        root.withArray("items").forEach(item -> {
            if (item instanceof ObjectNode itemObject) {
                String itemId = itemObject.path("itemId").asText("");
                BigDecimal amount = amountByItemId.get(itemId);
                if (amount != null) {
                    itemObject.put("price", amount);
                }
            }
        });
    }
}
