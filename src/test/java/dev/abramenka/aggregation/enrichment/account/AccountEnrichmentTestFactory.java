package dev.abramenka.aggregation.enrichment.account;

import dev.abramenka.aggregation.client.Accounts;
import dev.abramenka.aggregation.model.AggregationPart;
import dev.abramenka.aggregation.workflow.WorkflowExecutor;

public final class AccountEnrichmentTestFactory {

    private AccountEnrichmentTestFactory() {}

    public static AggregationPart accountEnrichment(Accounts accountClient) {
        return new AccountEnrichment(accountClient, new WorkflowExecutor());
    }
}
