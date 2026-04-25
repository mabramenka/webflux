package dev.abramenka.aggregation.part;

import dev.abramenka.aggregation.model.AggregationPartResult;
import dev.abramenka.aggregation.model.CompositionSpec;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Component
class AggregationPartResultApplicator {

    void apply(AggregationPartResult result, ObjectNode root) {
        switch (result) {
            case AggregationPartResult.ReplaceDocument replacement -> applyReplace(replacement, root);
            case AggregationPartResult.MergePatch patch -> applyMergePatch(patch, root);
            case AggregationPartResult.NoOp ignored -> {
                // Nothing to apply: the part intentionally produced no data.
            }
        }
    }

    private static void applyReplace(AggregationPartResult.ReplaceDocument replacement, ObjectNode root) {
        CompositionSpec compositionSpec = replacement.compositionSpec();
        if (isRootPath(compositionSpec.targetPath())) {
            replaceRoot(root, replacement.replacement());
            return;
        }
        PathTarget pathTarget = resolvePathTarget(root, compositionSpec.targetPath());
        pathTarget.parent().set(pathTarget.leafName(), replacement.replacement());
    }

    private static void applyMergePatch(AggregationPartResult.MergePatch patch, ObjectNode root) {
        CompositionSpec compositionSpec = patch.compositionSpec();
        if (isRootPath(compositionSpec.targetPath())) {
            applyPatch(patch.base(), patch.replacement(), root);
            return;
        }
        PathTarget pathTarget = resolvePathTarget(root, compositionSpec.targetPath());
        JsonNode currentNode = pathTarget.parent().optional(pathTarget.leafName()).orElse(null);
        if (!(currentNode instanceof ObjectNode currentObject)) {
            ObjectNode replacementTarget = patch.replacement().deepCopy();
            pathTarget.parent().set(pathTarget.leafName(), replacementTarget);
            return;
        }
        applyPatch(patch.base(), patch.replacement(), currentObject);
    }

    private static void replaceRoot(ObjectNode root, ObjectNode replacement) {
        root.removeAll();
        root.setAll(replacement);
    }

    private static boolean isRootPath(String targetPath) {
        return "/".equals(targetPath);
    }

    private static PathTarget resolvePathTarget(ObjectNode root, String targetPath) {
        if (!targetPath.startsWith("/")) {
            throw new IllegalArgumentException("Composition targetPath must be absolute: " + targetPath);
        }
        if ("/".equals(targetPath)) {
            throw new IllegalArgumentException("Root path must be handled separately");
        }
        String[] rawSegments = targetPath.substring(1).split("/");
        ObjectNode cursor = root;
        for (int i = 0; i < rawSegments.length - 1; i++) {
            String segment = rawSegments[i];
            JsonNode next = cursor.optional(segment).orElse(null);
            ObjectNode objectNode;
            if (!(next instanceof ObjectNode existingObjectNode)) {
                objectNode = cursor.objectNode();
                cursor.set(segment, objectNode);
            } else {
                objectNode = existingObjectNode;
            }
            cursor = objectNode;
        }
        return new PathTarget(cursor, rawSegments[rawSegments.length - 1]);
    }

    private static void applyPatch(ObjectNode base, ObjectNode replacement, ObjectNode target) {
        for (String propertyName : changedPropertyNames(base, replacement)) {
            JsonNode baseValue = base.optional(propertyName).orElse(null);
            JsonNode replacementValue = replacement.optional(propertyName).orElse(null);
            if (replacementValue == null) {
                target.remove(propertyName);
            } else if (baseValue instanceof ObjectNode baseObject
                    && replacementValue instanceof ObjectNode replacementObject
                    && target.optional(propertyName).orElse(null) instanceof ObjectNode targetObject) {
                applyPatch(baseObject, replacementObject, targetObject);
            } else if (baseValue instanceof ArrayNode baseArray
                    && replacementValue instanceof ArrayNode replacementArray
                    && target.optional(propertyName).orElse(null) instanceof ArrayNode targetArray
                    && canMergeArrayByIndex(baseArray, replacementArray, targetArray)) {
                applyArrayPatch(baseArray, replacementArray, targetArray);
            } else {
                target.set(propertyName, replacementValue.deepCopy());
            }
        }
    }

    private static void applyArrayPatch(ArrayNode base, ArrayNode replacement, ArrayNode target) {
        for (int i = 0; i < replacement.size(); i++) {
            JsonNode baseValue = base.get(i);
            JsonNode replacementValue = replacement.get(i);
            JsonNode targetValue = target.get(i);
            if (baseValue instanceof ObjectNode baseObject
                    && replacementValue instanceof ObjectNode replacementObject
                    && targetValue instanceof ObjectNode targetObject) {
                applyPatch(baseObject, replacementObject, targetObject);
            } else if (!baseValue.equals(replacementValue)) {
                target.set(i, replacementValue.deepCopy());
            }
        }
    }

    private static boolean canMergeArrayByIndex(ArrayNode base, ArrayNode replacement, ArrayNode target) {
        if (base.size() != replacement.size() || replacement.size() != target.size()) {
            return false;
        }
        for (int i = 0; i < replacement.size(); i++) {
            if (base.get(i).isObject() != replacement.get(i).isObject()
                    || replacement.get(i).isObject() != target.get(i).isObject()) {
                return false;
            }
        }
        return true;
    }

    private static Set<String> changedPropertyNames(ObjectNode base, ObjectNode replacement) {
        Set<String> propertyNames = new LinkedHashSet<>(base.propertyNames());
        propertyNames.addAll(replacement.propertyNames());
        propertyNames.removeIf(propertyName -> base.optional(propertyName).equals(replacement.optional(propertyName)));
        return propertyNames;
    }

    private record PathTarget(ObjectNode parent, String leafName) {}
}
