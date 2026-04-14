package com.example.aggregation.service.part;

import com.example.aggregation.client.OwnersClient;
import com.example.aggregation.service.AggregationContext;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

@Component
@Order(300)
public class OwnersPart extends KeyedArrayEnrichmentPart {

    private static final EnrichmentRule ENRICHMENT_RULE = EnrichmentRule.builder()
        .mainItems("$.data[*]", "basicDetails.owners[*].id", "basicDetails.owners[*].number")
        .responseItems("$.data[*]", "individual.number", "id")
        .requestKeysField("ids")
        .targetField("owners1")
        .build();

    private final OwnersClient ownersClient;

    public OwnersPart(OwnersClient ownersClient) {
        super(ENRICHMENT_RULE);
        this.ownersClient = ownersClient;
    }

    @Override
    public String name() {
        return "owners";
    }

    @Override
    public Mono<JsonNode> fetch(AggregationContext context) {
        ObjectNode request = requestWithKeys(context.mainResponse());
        return ownersClient.postOwners(request, context.downstreamRequest());
    }
}
