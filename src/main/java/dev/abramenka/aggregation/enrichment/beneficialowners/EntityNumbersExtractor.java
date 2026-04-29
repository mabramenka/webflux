package dev.abramenka.aggregation.enrichment.beneficialowners;

import java.util.LinkedHashSet;
import java.util.Set;
import tools.jackson.databind.JsonNode;

final class EntityNumbersExtractor {

    private static final String ENTITY_FIELD = "entity";
    private static final String NUMBER_FIELD = "number";

    private EntityNumbersExtractor() {}

    static Set<String> childNumbers(JsonNode entityOwnerNode) {
        JsonNode entity = entityOwnerNode.path(ENTITY_FIELD);
        if (!entity.isObject()) {
            return Set.of();
        }
        JsonNode structures = entity.path("ownershipStructure");
        if (!structures.isArray()) {
            return Set.of();
        }
        Set<String> numbers = new LinkedHashSet<>();
        for (JsonNode structure : structures.values()) {
            collect(structure.path("principalOwners"), numbers);
            collect(structure.path("indirectOwners"), numbers);
        }
        return numbers;
    }

    static boolean isEntity(JsonNode ownerNode) {
        return ownerNode.path(ENTITY_FIELD).isObject();
    }

    static boolean isIndividual(JsonNode ownerNode) {
        return ownerNode.path("individual").isObject();
    }

    static String ownerNumber(JsonNode ownerNode) {
        String individualNumber =
                ownerNode.path("individual").path(NUMBER_FIELD).asString("");
        if (!individualNumber.isBlank()) {
            return individualNumber;
        }
        return ownerNode.path(ENTITY_FIELD).path(NUMBER_FIELD).asString("");
    }

    private static void collect(JsonNode ownersArray, Set<String> out) {
        if (!ownersArray.isArray()) {
            return;
        }
        for (JsonNode owner : ownersArray.values()) {
            String number = owner.path("memberDetails").path(NUMBER_FIELD).asString("");
            if (!number.isBlank()) {
                out.add(number);
            }
        }
    }
}
