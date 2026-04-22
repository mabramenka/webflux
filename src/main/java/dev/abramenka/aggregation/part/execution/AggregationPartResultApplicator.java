package dev.abramenka.aggregation.part.execution;

import dev.abramenka.aggregation.model.AggregationPartResult;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
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
            } else {
                target.set(propertyName, replacementValue.deepCopy());
            }
        }
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
