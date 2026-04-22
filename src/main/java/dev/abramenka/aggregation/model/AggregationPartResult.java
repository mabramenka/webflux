package dev.abramenka.aggregation.model;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

public interface AggregationPartResult {

    String partName();

    void applyTo(ObjectNode root);

    static AggregationPartResult mutation(String partName, Consumer<ObjectNode> mutator) {
        return new AggregationPartResult() {
            @Override
            public String partName() {
                return partName;
            }

            @Override
            public void applyTo(ObjectNode root) {
                mutator.accept(root);
            }
        };
    }

    static AggregationPartResult patch(String partName, ObjectNode base, ObjectNode replacement) {
        return mutation(partName, root -> applyPatch(base, replacement, root));
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
