package dev.abramenka.aggregation.enrichment.accountgroup;

import dev.abramenka.aggregation.client.AccountGroups;
import dev.abramenka.aggregation.model.AggregationPart;
import tools.jackson.databind.ObjectMapper;

public final class AccountGroupEnrichmentTestFactory {

    private AccountGroupEnrichmentTestFactory() {}

    public static AggregationPart accountGroupEnrichment(AccountGroups accountGroups, ObjectMapper objectMapper) {
        return new AccountGroupEnrichment(accountGroups, objectMapper);
    }
}
