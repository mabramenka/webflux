package dev.abramenka.aggregation.enrichment.beneficialowners;

import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Component
final class BeneficialOwnersDetailsPayload {

    private static final String DATA_FIELD = "data";
    private static final String OWNERS_FIELD = "owners1";
    private static final String TARGET_FIELD = "beneficialOwnersDetails";
    private static final String DATA_INDEX_FIELD = "dataIndex";
    private static final String OWNER_INDEX_FIELD = "ownerIndex";
    private static final String DETAILS_FIELD = "details";

    private final ObjectMapper objectMapper;

    BeneficialOwnersDetailsPayload(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    ObjectNode response(List<ResolvedEntity> resolvedEntities) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode data = response.putArray(DATA_FIELD);
        for (ResolvedEntity entity : resolvedEntities) {
            ObjectNode item = data.addObject();
            item.put(DATA_INDEX_FIELD, entity.dataIndex());
            item.put(OWNER_INDEX_FIELD, entity.ownerIndex());
            item.set(DETAILS_FIELD, entity.details());
        }
        return response;
    }

    void merge(ObjectNode root, JsonNode enrichmentResponse) {
        JsonNode data = enrichmentResponse.path(DATA_FIELD);
        if (!data.isArray()) {
            return;
        }
        for (JsonNode item : data.values()) {
            int dataIndex = item.path(DATA_INDEX_FIELD).asInt(-1);
            int ownerIndex = item.path(OWNER_INDEX_FIELD).asInt(-1);
            JsonNode details = item.path(DETAILS_FIELD);
            JsonNode owner =
                    root.path(DATA_FIELD).path(dataIndex).path(OWNERS_FIELD).path(ownerIndex);
            if (owner instanceof ObjectNode ownerObject && details.isArray()) {
                ownerObject.set(TARGET_FIELD, details.deepCopy());
            }
        }
    }
}
