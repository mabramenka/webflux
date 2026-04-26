package dev.abramenka.aggregation.enrichment.owners;

import dev.abramenka.aggregation.client.Owners;
import dev.abramenka.aggregation.enrichment.account.AccountEnrichmentTestFactory;
import dev.abramenka.aggregation.model.AggregationPart;

public final class OwnersEnrichmentTestFactory {

    private OwnersEnrichmentTestFactory() {}

    public static AggregationPart ownersEnrichment(Owners ownersClient) {
        return new OwnersEnrichment(ownersClient, AccountEnrichmentTestFactory.noopWorkflowExecutor());
    }
}
