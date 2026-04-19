package dev.abramenka.aggregation.service;

import dev.abramenka.aggregation.error.InvalidAggregationRequestException;
import java.util.Optional;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

@Component
public class AccountGroupRequestFactory {

    private static final String IDS_FIELD = "ids";

    public ObjectNode from(ObjectNode inboundRequest) {
        ObjectNode request = JsonNodeFactory.instance.objectNode();
        request.set(IDS_FIELD, requiredStringArray(inboundRequest, IDS_FIELD));
        return request;
    }

    private static ArrayNode requiredStringArray(ObjectNode request, String fieldName) {
        JsonNode field = request.path(fieldName);
        if (field.isMissingNode() || field.isNull()) {
            throw new InvalidAggregationRequestException("'" + fieldName + "' is required");
        }
        if (!field.isArray()) {
            throw new InvalidAggregationRequestException("'" + fieldName + "' must be an array of non-blank strings");
        }

        ArrayNode ids = JsonNodeFactory.instance.arrayNode();
        field.forEach(item -> ids.add(stringValue(item)
                .orElseThrow(() -> new InvalidAggregationRequestException(
                        "'" + fieldName + "' values must be non-blank strings"))));
        if (ids.isEmpty()) {
            throw new InvalidAggregationRequestException("'" + fieldName + "' must contain at least one value");
        }
        return ids;
    }

    private static Optional<String> stringValue(JsonNode field) {
        return field.stringValueOpt().map(String::trim).filter(value -> !value.isBlank());
    }
}
