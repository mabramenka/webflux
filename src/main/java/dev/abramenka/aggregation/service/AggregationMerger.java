package dev.abramenka.aggregation.service;

import dev.abramenka.aggregation.enrichment.AggregationEnrichment;
import dev.abramenka.aggregation.error.DownstreamClientException;
import dev.abramenka.aggregation.service.EnrichmentFetchResult.Success;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
        Map<String, Success> successByName = HashMap.newHashMap(results.size());
        for (EnrichmentFetchResult result : results) {
            if (result instanceof Success success) {
                successByName.put(success.name(), success);
            }
        }

        for (AggregationEnrichment enrichment : enabledEnrichments) {
            Success success = successByName.get(enrichment.name());
            if (success != null) {
                enrichment.merge(root, success.response());
            }
        }

        return root;
    }
}
