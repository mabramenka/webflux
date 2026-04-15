package com.example.aggregation.enrichment;

import com.example.aggregation.model.AggregationContext;
import com.example.aggregation.enrichment.keyed.EnrichmentRule;
import com.example.aggregation.enrichment.keyed.KeyedArrayEnrichment;
import com.example.aggregation.client.WebClientAccountClient;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

@Component
@Order(200)
public class AccountEnrichment extends KeyedArrayEnrichment {

    private static final String CURRENCY_FIELD = "currency";

    private static final EnrichmentRule ENRICHMENT_RULE = EnrichmentRule.builder()
        .mainItems("$.data[*]", "accounts[*].id")
        .responseItems("$.data[*]", "id")
        .requestKeysField("ids")
        .targetField("account1")
        .build();

    private final WebClientAccountClient accountClient;

    public AccountEnrichment(WebClientAccountClient accountClient) {
        super(ENRICHMENT_RULE);
        this.accountClient = accountClient;
    }

    @Override
    public String name() {
        return "account";
    }

    @Override
    public Mono<JsonNode> fetch(AggregationContext context) {
        ObjectNode request = requestWithKeys(context.accountGroupResponse());
        request.put(
            CURRENCY_FIELD,
            context.accountGroupResponse().path(CURRENCY_FIELD)
                .asString(context.inboundRequest().path(CURRENCY_FIELD).asString("USD"))
        );

        return accountClient.postAccount(request, context.clientRequestContext());
    }
}
