package dev.abramenka.aggregation.workflow.recursive;

import dev.abramenka.aggregation.error.EnrichmentDependencyException;
import dev.abramenka.aggregation.patch.JsonPatchBuilder;
import dev.abramenka.aggregation.patch.JsonPatchDocument;
import dev.abramenka.aggregation.patch.JsonPointerBuilder;
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
public final class BeneficialOwnersTraversalReducer implements TraversalReducer {

    private static final String REDUCER_PART_NAME = "beneficialOwners";

    private final JsonNode rootForWriteDecision;

    public BeneficialOwnersTraversalReducer(JsonNode rootForWriteDecision) {
        this.rootForWriteDecision = Objects.requireNonNull(rootForWriteDecision, "rootForWriteDecision");
    }

    @Override
    public JsonPatchDocument reduce(JsonNode traversalResult) {
        Objects.requireNonNull(traversalResult, "traversalResult");

        JsonNode groupsNode = traversalResult.get("groups");
        if (!(groupsNode instanceof ArrayNode groups)) {
            throw contractViolation("Traversal result must contain array field 'groups'");
        }

        JsonPatchBuilder patchBuilder = JsonPatchBuilder.create();
        for (int groupIndex = 0; groupIndex < groups.size(); groupIndex++) {
            JsonNode groupNode = groups.get(groupIndex);
            if (!(groupNode instanceof ObjectNode group)) {
                throw contractViolation("Traversal group at index " + groupIndex + " must be an object");
            }

            JsonNode targetMetadataNode = group.get("targetMetadata");
            if (!(targetMetadataNode instanceof ObjectNode targetMetadata)) {
                throw contractViolation(
                        "Traversal group at index " + groupIndex + " must contain object field 'targetMetadata'");
            }
            int dataIndex = requiredIndex(targetMetadata, "dataIndex", groupIndex);
            int ownerIndex = requiredIndex(targetMetadata, "ownerIndex", groupIndex);

            JsonNode resolvedNodesNode = group.get("resolvedNodes");
            if (!(resolvedNodesNode instanceof ArrayNode resolvedNodes)) {
                throw contractViolation(
                        "Traversal group at index " + groupIndex + " must contain array field 'resolvedNodes'");
            }

            ArrayNode details = resolvedNodes.arrayNode();
            for (int nodeIndex = 0; nodeIndex < resolvedNodes.size(); nodeIndex++) {
                JsonNode resolvedNode = resolvedNodes.get(nodeIndex);
                JsonNode payload = resolvedNode == null ? null : resolvedNode.get("node");
                if (payload == null || payload.isNull()) {
                    throw contractViolation("Traversal group at index "
                            + groupIndex
                            + " has resolvedNodes["
                            + nodeIndex
                            + "] without required field 'node'");
                }
                details.add(payload.deepCopy());
            }

            String writePointer = writePointer(dataIndex, ownerIndex);
            JsonNode owner = rootForWriteDecision
                    .path("data")
                    .path(dataIndex)
                    .path("owners1")
                    .path(ownerIndex);
            if (!(owner instanceof ObjectNode ownerObject)) {
                throw contractViolation("Traversal group at index "
                        + groupIndex
                        + " points to missing owner target for dataIndex="
                        + dataIndex
                        + ", ownerIndex="
                        + ownerIndex);
            }
            if (ownerObject.has("beneficialOwnersDetails")) {
                patchBuilder.replace(writePointer, details);
            } else {
                patchBuilder.add(writePointer, details);
            }
        }

        return patchBuilder.build();
    }

    private static int requiredIndex(ObjectNode targetMetadata, String fieldName, int groupIndex) {
        JsonNode value = targetMetadata.get(fieldName);
        if (value == null || !value.isIntegralNumber()) {
            throw contractViolation(
                    "Traversal group at index " + groupIndex + " targetMetadata." + fieldName + " must be an integer");
        }
        int index = value.intValue();
        if (index < 0) {
            throw contractViolation("Traversal group at index "
                    + groupIndex
                    + " targetMetadata."
                    + fieldName
                    + " must be non-negative");
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
