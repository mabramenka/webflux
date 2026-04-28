package dev.abramenka.aggregation.workflow;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

/**
 * Stores named values produced by {@link WorkflowStep#execute(WorkflowContext)} so later steps can
 * read them. Backed by a {@link LinkedHashMap} so iteration order matches insertion.
 */
public final class WorkflowVariableStore {

    private final Map<String, JsonNode> values = new LinkedHashMap<>();

    public void put(String name, JsonNode value) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(value, "value");
        if (name.isBlank()) {
            throw new IllegalArgumentException("Variable name must not be blank");
        }
        if (values.containsKey(name)) {
            throw new IllegalStateException("Workflow variable already set: " + name);
        }
        values.put(name, value);
    }

    public Optional<JsonNode> get(String name) {
        return Optional.ofNullable(values.get(name));
    }

    public boolean contains(String name) {
        return values.containsKey(name);
    }
}
