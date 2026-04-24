package dev.abramenka.aggregation.enrichment.owners;

import dev.abramenka.aggregation.client.DownstreamClientResponses;
import dev.abramenka.aggregation.client.HttpServiceGroups;
import dev.abramenka.aggregation.client.Owners;
import dev.abramenka.aggregation.enrichment.support.keyed.EnrichmentRule;
import dev.abramenka.aggregation.enrichment.support.keyed.KeyedArrayEnrichment;
import dev.abramenka.aggregation.model.AggregationContext;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
@Order(Ordered.LOWEST_PRECEDENCE - 1)
class OwnersEnrichment extends KeyedArrayEnrichment {

    private static final String CLIENT_NAME = HttpServiceGroups.downstreamClientName(HttpServiceGroups.OWNERS);

    private static final EnrichmentRule ENRICHMENT_RULE = EnrichmentRule.builder()
            .mainItems("$.data[*]", "basicDetails.owners[*].id", "basicDetails.owners[*].number")
            .responseItems("$.data[*]", "individual.number", "id")
            .requestKeysField("ids")
            .targetField("owners1")
            .build();

    private final Owners ownersClient;

    OwnersEnrichment(Owners ownersClient, ObjectMapper objectMapper) {
        super(ENRICHMENT_RULE, objectMapper);
        this.ownersClient = ownersClient;
    }

    @Override
    public String name() {
        return "owners";
    }

    @Override
    public Mono<JsonNode> fetch(AggregationContext context) {
        ObjectNode request = requestWithKeys(context);
        return DownstreamClientResponses.optionalBody(
                CLIENT_NAME, ownersClient.fetchOwners(request, context.clientRequestContext()));
    }
}
