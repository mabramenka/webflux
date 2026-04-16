package com.example.aggregation.service;

import com.example.aggregation.enrichment.AggregationEnrichment;
import com.example.aggregation.error.DownstreamClientException;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

@Component
public class AggregationMerger {

    public ObjectNode mutableRoot(String clientName, JsonNode accountGroupResponse) {
        if (!accountGroupResponse.isObject()) {
            throw DownstreamClientException.gatewayError(
                    clientName, "account group client returned a non-object JSON response");
        }
        return (ObjectNode) accountGroupResponse.deepCopy();
    }

    JsonNode merge(
            ObjectNode root, List<AggregationEnrichment> enabledEnrichments, List<EnrichmentFetchResult> results) {
        Map<String, EnrichmentFetchResult> resultByName =
                results.stream().collect(Collectors.toMap(EnrichmentFetchResult::name, Function.identity()));

        enabledEnrichments.stream()
                .map(enrichment -> resultByName.get(enrichment.name()))
                .filter(result -> result != null && result.successful())
                .forEach(result -> result.mergeInto(root));

        return root;
    }
}
