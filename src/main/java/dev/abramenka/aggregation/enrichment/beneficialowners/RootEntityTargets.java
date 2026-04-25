package dev.abramenka.aggregation.enrichment.beneficialowners;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

@Component
final class RootEntityTargets {

    private static final String DATA_FIELD = "data";
    private static final String OWNERS_FIELD = "owners1";

    List<RootEntityTarget> collect(JsonNode root) {
        JsonNode dataArray = root.path(DATA_FIELD);
        if (!dataArray.isArray()) {
            return List.of();
        }
        List<RootEntityTarget> entities = new ArrayList<>();
        int dataIndex = 0;
        for (JsonNode item : dataArray.values()) {
            JsonNode ownersArray = item.path(OWNERS_FIELD);
            if (!ownersArray.isArray()) {
                dataIndex++;
                continue;
            }
            int ownerIndex = 0;
            for (JsonNode owner : ownersArray.values()) {
                if (owner instanceof ObjectNode ownerObject && EntityNumbersExtractor.isEntity(ownerObject)) {
                    entities.add(new RootEntityTarget(dataIndex, ownerIndex, ownerObject));
                }
                ownerIndex++;
            }
            dataIndex++;
        }
        return entities;
    }
}
