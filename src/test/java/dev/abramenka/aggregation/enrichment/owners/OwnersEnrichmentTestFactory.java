package dev.abramenka.aggregation.enrichment.owners;

import dev.abramenka.aggregation.client.Owners;
import dev.abramenka.aggregation.model.AggregationPart;
import tools.jackson.databind.ObjectMapper;

public final class OwnersEnrichmentTestFactory {

    private OwnersEnrichmentTestFactory() {}

    public static AggregationPart ownersEnrichment(Owners ownersClient, ObjectMapper objectMapper) {
        return new OwnersEnrichment(ownersClient, objectMapper);
    }
}
