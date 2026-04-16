package com.example.aggregation.service;

import com.example.aggregation.error.InvalidAggregationRequestException;
import java.util.Optional;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

@Component
public class AccountGroupRequestFactory {

    private static final String CUSTOMER_ID_FIELD = "customerId";

    public ObjectNode from(ObjectNode inboundRequest) {
        ObjectNode request = JsonNodeFactory.instance.objectNode();
        request.put(CUSTOMER_ID_FIELD, requiredString(inboundRequest, CUSTOMER_ID_FIELD));
        request.put("market", optionalString(inboundRequest, "market", "US"));
        request.put("includeItems", optionalBoolean(inboundRequest, "includeItems", true));
        return request;
    }

    private static String requiredString(ObjectNode request, String fieldName) {
        JsonNode field = request.path(fieldName);
        if (field.isMissingNode() || field.isNull()) {
            throw new InvalidAggregationRequestException("'" + fieldName + "' is required");
        }
        return stringValue(field)
                .orElseThrow(
                        () -> new InvalidAggregationRequestException("'" + fieldName + "' must be a non-blank string"));
    }

    private static String optionalString(ObjectNode request, String fieldName, String defaultValue) {
        JsonNode field = request.path(fieldName);
        if (field.isMissingNode() || field.isNull()) {
            return defaultValue;
        }
        return stringValue(field)
                .orElseThrow(
                        () -> new InvalidAggregationRequestException("'" + fieldName + "' must be a non-blank string"));
    }

    private static Optional<String> stringValue(JsonNode field) {
        return field.stringValueOpt().map(String::trim).filter(value -> !value.isBlank());
    }

    private static boolean optionalBoolean(ObjectNode request, String fieldName, boolean defaultValue) {
        JsonNode field = request.path(fieldName);
        if (field.isMissingNode() || field.isNull()) {
            return defaultValue;
        }
        return field.booleanValueOpt()
                .orElseThrow(() -> new InvalidAggregationRequestException("'" + fieldName + "' must be a boolean"));
    }
}
