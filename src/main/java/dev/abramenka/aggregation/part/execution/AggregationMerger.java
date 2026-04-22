package dev.abramenka.aggregation.part.execution;

import dev.abramenka.aggregation.error.DownstreamClientException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

@Component
public class AggregationMerger {

    ObjectNode mutableRoot(String clientName, JsonNode accountGroupResponse) {
        if (!accountGroupResponse.isObject()) {
            throw DownstreamClientException.transport(clientName, null);
        }
        return (ObjectNode) accountGroupResponse.deepCopy();
    }
}
