package dev.abramenka.aggregation.enrichment.beneficialowners;

import dev.abramenka.aggregation.error.EnrichmentDependencyException;
import dev.abramenka.aggregation.patch.JsonPatchBuilder;
import dev.abramenka.aggregation.patch.JsonPatchDocument;
import dev.abramenka.aggregation.patch.JsonPointerBuilder;
import dev.abramenka.aggregation.workflow.recursive.TraversalReducer;
import java.util.Objects;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Beneficial-owners write-back reducer for grouped traversal results.
 *
 * <p>Consumes traversal result JSON:
 *
 * <pre>
 * {
 *   "groups": [
 *     {
 *       "targetMetadata": {"dataIndex": 0, "ownerIndex": 0},
 *       "resolvedNodes": [{"node": {...}}]
 *     }
 *   ]
 * }
 * </pre>
 *
 * and emits patch writes to:
 *
 * <pre>
 * /data/{dataIndex}/owners1/{ownerIndex}/beneficialOwnersDetails
 * </pre>
 */
final class BeneficialOwnersTraversalReducer implements TraversalReducer {

    private static final String REDUCER_PART_NAME = "beneficialOwners";
    private static final String TRAVERSAL_GROUP_AT_INDEX = "Traversal group at index ";

    @Override
    public JsonPatchDocument reduce(JsonNode traversalResult, JsonNode rootForWriteDecision) {
        Objects.requireNonNull(traversalResult, "traversalResult");
        Objects.requireNonNull(rootForWriteDecision, "rootForWriteDecision");

        ArrayNode groups = requiredGroups(traversalResult);
        JsonPatchBuilder patchBuilder = JsonPatchBuilder.create();
        for (int groupIndex = 0; groupIndex < groups.size(); groupIndex++) {
            reduceGroup(groups.get(groupIndex), groupIndex, rootForWriteDecision, patchBuilder);
        }

        return patchBuilder.build();
    }

    private static ArrayNode requiredGroups(JsonNode traversalResult) {
        JsonNode groupsNode = traversalResult.get("groups");
        if (!(groupsNode instanceof ArrayNode groups)) {
            throw contractViolation("Traversal result must contain array field 'groups'");
        }
        return groups;
    }

    private static void reduceGroup(
            JsonNode groupNode, int groupIndex, JsonNode rootForWriteDecision, JsonPatchBuilder patchBuilder) {
        ObjectNode group = requiredGroup(groupNode, groupIndex);
        ObjectNode targetMetadata = requiredObjectField(group, "targetMetadata", groupIndex);
        int dataIndex = requiredIndex(targetMetadata, "dataIndex", groupIndex);
        int ownerIndex = requiredIndex(targetMetadata, "ownerIndex", groupIndex);
        ArrayNode resolvedNodes = requiredArrayField(group, "resolvedNodes", groupIndex);
        ArrayNode details = detailsFrom(resolvedNodes, groupIndex);
        applyDetailsPatch(rootForWriteDecision, patchBuilder, groupIndex, dataIndex, ownerIndex, details);
    }

    private static ObjectNode requiredGroup(JsonNode groupNode, int groupIndex) {
        if (!(groupNode instanceof ObjectNode group)) {
            throw contractViolation(TRAVERSAL_GROUP_AT_INDEX + groupIndex + " must be an object");
        }
        return group;
    }

    private static ObjectNode requiredObjectField(ObjectNode group, String fieldName, int groupIndex) {
        JsonNode field = group.get(fieldName);
        if (!(field instanceof ObjectNode objectField)) {
            throw contractViolation(
                    TRAVERSAL_GROUP_AT_INDEX + groupIndex + " must contain object field '" + fieldName + "'");
        }
        return objectField;
    }

    private static ArrayNode requiredArrayField(ObjectNode group, String fieldName, int groupIndex) {
        JsonNode field = group.get(fieldName);
        if (!(field instanceof ArrayNode arrayField)) {
            throw contractViolation(
                    TRAVERSAL_GROUP_AT_INDEX + groupIndex + " must contain array field '" + fieldName + "'");
        }
        return arrayField;
    }

    private static ArrayNode detailsFrom(ArrayNode resolvedNodes, int groupIndex) {
        ArrayNode details = resolvedNodes.arrayNode();
        for (int nodeIndex = 0; nodeIndex < resolvedNodes.size(); nodeIndex++) {
            details.add(requiredPayload(resolvedNodes.get(nodeIndex), groupIndex, nodeIndex)
                    .deepCopy());
        }
        return details;
    }

    private static JsonNode requiredPayload(JsonNode resolvedNode, int groupIndex, int nodeIndex) {
        JsonNode payload = resolvedNode.get("node");
        if (payload == null || payload.isNull()) {
            throw contractViolation(TRAVERSAL_GROUP_AT_INDEX
                    + groupIndex
                    + " has resolvedNodes["
                    + nodeIndex
                    + "] without required field 'node'");
        }
        return payload;
    }

    private static void applyDetailsPatch(
            JsonNode rootForWriteDecision,
            JsonPatchBuilder patchBuilder,
            int groupIndex,
            int dataIndex,
            int ownerIndex,
            ArrayNode details) {
        String writePointer = writePointer(dataIndex, ownerIndex);
        ObjectNode ownerObject = requiredOwnerTarget(rootForWriteDecision, groupIndex, dataIndex, ownerIndex);
        if (ownerObject.has("beneficialOwnersDetails")) {
            patchBuilder.replace(writePointer, details);
        } else {
            patchBuilder.add(writePointer, details);
        }
    }

    private static ObjectNode requiredOwnerTarget(
            JsonNode rootForWriteDecision, int groupIndex, int dataIndex, int ownerIndex) {
        JsonNode owner = rootForWriteDecision
                .path("data")
                .path(dataIndex)
                .path("owners1")
                .path(ownerIndex);
        if (!(owner instanceof ObjectNode ownerObject)) {
            throw contractViolation(TRAVERSAL_GROUP_AT_INDEX
                    + groupIndex
                    + " points to missing owner target for dataIndex="
                    + dataIndex
                    + ", ownerIndex="
                    + ownerIndex);
        }
        return ownerObject;
    }

    private static int requiredIndex(ObjectNode targetMetadata, String fieldName, int groupIndex) {
        JsonNode value = targetMetadata.get(fieldName);
        if (value == null || !value.isIntegralNumber()) {
            throw contractViolation(
                    TRAVERSAL_GROUP_AT_INDEX + groupIndex + " targetMetadata." + fieldName + " must be an integer");
        }
        int index = value.intValue();
        if (index < 0) {
            throw contractViolation(
                    TRAVERSAL_GROUP_AT_INDEX + groupIndex + " targetMetadata." + fieldName + " must be non-negative");
        }
        return index;
    }

    private static String writePointer(int dataIndex, int ownerIndex) {
        return JsonPointerBuilder.create()
                .field("data")
                .index(dataIndex)
                .field("owners1")
                .index(ownerIndex)
                .field("beneficialOwnersDetails")
                .build();
    }

    private static EnrichmentDependencyException contractViolation(String message) {
        return EnrichmentDependencyException.contractViolation(
                REDUCER_PART_NAME, new IllegalArgumentException(message));
    }
}
