package com.example.aggregation.service.part;

import com.example.aggregation.service.AggregationContext;
import com.example.aggregation.service.AggregationPart;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

public abstract class MatchedArrayEnrichmentPart implements AggregationPart {

    @Override
    public boolean supports(AggregationContext context) {
        return !targets(context.mainResponse()).isEmpty();
    }

    @Override
    public void merge(ObjectNode root, JsonNode partResponse) {
        Map<String, JsonNode> responseById = responseById(partResponse);
        targets(root).forEach(target -> attach(target, responseById));
    }

    protected ArrayNode targetIds(JsonNode root) {
        ArrayNode ids = JsonNodeFactory.instance.arrayNode();
        targets(root).stream()
            .map(Target::id)
            .forEach(ids::add);
        return ids;
    }

    protected ObjectNode requestWithTargetIds(JsonNode root) {
        ObjectNode request = JsonNodeFactory.instance.objectNode();
        request.set(targetIdsRequestField(), targetIds(root));
        return request;
    }

    protected abstract String targetIdsRequestField();

    protected abstract List<Target> targets(JsonNode root);

    protected abstract String responseArrayField();

    protected abstract String responseIdField();

    protected abstract String targetField();

    private Map<String, JsonNode> responseById(JsonNode response) {
        Map<String, JsonNode> responseById = new HashMap<>();
        response.path(responseArrayField()).forEach(entry -> {
            String id = entry.path(responseIdField()).asString("");
            if (!id.isBlank()) {
                responseById.put(id, entry);
            }
        });
        return responseById;
    }

    private void attach(Target target, Map<String, JsonNode> responseById) {
        JsonNode response = responseById.get(target.id());
        if (response != null) {
            target.node().set(targetField(), response);
        }
    }

    protected record Target(String id, ObjectNode node) {
    }
}
