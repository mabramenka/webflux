package dev.abramenka.aggregation.part;

import dev.abramenka.aggregation.error.DownstreamClientException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

@Component
class AggregationRootFactory {

    ObjectNode mutableRoot(String clientName, JsonNode accountGroupResponse) {
        if (!accountGroupResponse.isObject()) {
            throw DownstreamClientException.contractViolation(clientName);
        }
        return (ObjectNode) accountGroupResponse.deepCopy();
    }
}
