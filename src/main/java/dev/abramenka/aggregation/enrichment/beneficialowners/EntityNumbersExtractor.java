package dev.abramenka.aggregation.enrichment.beneficialowners;

import java.util.LinkedHashSet;
import java.util.Set;
import tools.jackson.databind.JsonNode;

final class EntityNumbersExtractor {

    private EntityNumbersExtractor() {}

    static Set<String> childNumbers(JsonNode entityOwnerNode) {
        JsonNode entity = entityOwnerNode.path("entity");
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
        return ownerNode.path("entity").isObject();
    }

    static boolean isIndividual(JsonNode ownerNode) {
        return ownerNode.path("individual").isObject();
    }

    static String ownerNumber(JsonNode ownerNode) {
        String individualNumber = ownerNode.path("individual").path("number").asString("");
        if (!individualNumber.isBlank()) {
            return individualNumber;
        }
        return ownerNode.path("entity").path("number").asString("");
    }

    private static void collect(JsonNode ownersArray, Set<String> out) {
        if (!ownersArray.isArray()) {
            return;
        }
        for (JsonNode owner : ownersArray.values()) {
            String number = owner.path("memberDetails").path("number").asString("");
            if (!number.isBlank()) {
                out.add(number);
            }
        }
    }
}
