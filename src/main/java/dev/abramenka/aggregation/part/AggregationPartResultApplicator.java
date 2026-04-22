package dev.abramenka.aggregation.part;

import dev.abramenka.aggregation.model.AggregationPartResult;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Component
class AggregationPartResultApplicator {

    void apply(AggregationPartResult result, ObjectNode root) {
        switch (result) {
            case AggregationPartResult.ReplaceDocument replacement -> replaceRoot(root, replacement.replacement());
            case AggregationPartResult.MergePatch patch -> applyPatch(patch.base(), patch.replacement(), root);
        }
    }

    private static void replaceRoot(ObjectNode root, ObjectNode replacement) {
        root.removeAll();
        root.setAll(replacement);
    }

    private static void applyPatch(ObjectNode base, ObjectNode replacement, ObjectNode target) {
        for (String propertyName : changedPropertyNames(base, replacement)) {
            @Nullable JsonNode baseValue = base.optional(propertyName).orElse(null);
            @Nullable
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
        propertyNames.removeIf(
                propertyName -> valuesEqual(base.optional(propertyName), replacement.optional(propertyName)));
        return propertyNames;
    }

    private static boolean valuesEqual(Optional<JsonNode> left, Optional<JsonNode> right) {
        if (left.isEmpty() || right.isEmpty()) {
            return left.isEmpty() && right.isEmpty();
        }
        return left.orElseThrow().equals(right.orElseThrow());
    }
}
