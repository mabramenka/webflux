package dev.abramenka.aggregation.enrichment.account;

import dev.abramenka.aggregation.client.Accounts;
import dev.abramenka.aggregation.model.AggregationPart;
import tools.jackson.databind.ObjectMapper;

public final class AccountEnrichmentTestFactory {

    private AccountEnrichmentTestFactory() {}

    public static AggregationPart accountEnrichment(Accounts accountClient, ObjectMapper objectMapper) {
        return new AccountEnrichment(accountClient, objectMapper);
    }
}
