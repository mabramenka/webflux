package dev.abramenka.aggregation.enrichment;

import dev.abramenka.aggregation.client.Accounts;
import dev.abramenka.aggregation.enrichment.keyed.EnrichmentRule;
import dev.abramenka.aggregation.enrichment.keyed.KeyedArrayEnrichment;
import dev.abramenka.aggregation.model.AggregationContext;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
@Order(Ordered.LOWEST_PRECEDENCE - 2)
public class AccountEnrichment extends KeyedArrayEnrichment {

    private static final EnrichmentRule ENRICHMENT_RULE = EnrichmentRule.builder()
            .mainItems("$.data[*]", "accounts[*].id")
            .responseItems("$.data[*]", "id")
            .requestKeysField("ids")
            .targetField("account1")
            .build();

    private final Accounts accountClient;

    public AccountEnrichment(Accounts accountClient, ObjectMapper objectMapper) {
        super(ENRICHMENT_RULE, objectMapper);
        this.accountClient = accountClient;
    }

    @Override
    public String name() {
        return "account";
    }

    @Override
    public Mono<JsonNode> fetch(AggregationContext context) {
        ObjectNode request = requestWithKeys(context.accountGroupResponse());
        return accountClient.fetchAccounts(request, context.clientRequestContext());
    }
}
