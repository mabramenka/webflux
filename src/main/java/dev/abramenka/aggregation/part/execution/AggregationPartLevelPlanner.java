package dev.abramenka.aggregation.part.execution;

import dev.abramenka.aggregation.model.AggregationPart;
import dev.abramenka.aggregation.model.AggregationPartSelection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class AggregationPartLevelPlanner {

    private final List<AggregationPart> orderedParts;
    private final Map<String, AggregationPart> partsByName;

    AggregationPartLevelPlanner(List<AggregationPart> orderedParts, Map<String, AggregationPart> partsByName) {
        this.orderedParts = List.copyOf(orderedParts);
        this.partsByName = Map.copyOf(partsByName);
    }

    List<List<AggregationPart>> selectedLevels(AggregationPartSelection effectiveSelection) {
        Map<Integer, List<AggregationPart>> levels = new LinkedHashMap<>();
        Map<String, Integer> depths = new LinkedHashMap<>();
        orderedParts.stream()
                .filter(part -> effectiveSelection.includes(part.name()))
                .forEach(part -> levels.computeIfAbsent(
                                depth(part, effectiveSelection, depths), ignored -> new ArrayList<>())
                        .add(part));
        return levels.values().stream().map(List::copyOf).toList();
    }

    private int depth(AggregationPart part, AggregationPartSelection effectiveSelection, Map<String, Integer> depths) {
        Integer existing = depths.get(part.name());
        if (existing != null) {
            return existing;
        }

        int depth = part.dependencies().stream()
                .filter(effectiveSelection::includes)
                .map(this::partByName)
                .mapToInt(dependency -> depth(dependency, effectiveSelection, depths) + 1)
                .max()
                .orElse(0);
        depths.put(part.name(), depth);
        return depth;
    }

    private AggregationPart partByName(String name) {
        AggregationPart part = partsByName.get(name);
        if (part == null) {
            throw new IllegalStateException("Unknown aggregation component dependency: " + name);
        }
        return part;
    }
}
