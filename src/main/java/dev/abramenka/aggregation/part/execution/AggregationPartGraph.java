package dev.abramenka.aggregation.part.execution;

import dev.abramenka.aggregation.enrichment.AggregationEnrichment;
import dev.abramenka.aggregation.model.AggregationPart;
import dev.abramenka.aggregation.model.AggregationPartSelection;
import dev.abramenka.aggregation.postprocessor.AggregationPostProcessor;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class AggregationPartGraph {

    private final List<AggregationPart> parts;
    private final List<AggregationPart> orderedParts;
    private final Map<String, AggregationPart> partsByName;

    private AggregationPartGraph(List<AggregationPart> parts) {
        this.parts = List.copyOf(parts);
        this.partsByName = buildPartsIndex(this.parts);
        validateDependencies(this.parts, this.partsByName);
        this.orderedParts = orderByDependencies(this.parts, this.partsByName);
    }

    static AggregationPartGraph from(
            List<AggregationEnrichment> enrichments, List<AggregationPostProcessor> postProcessors) {
        List<AggregationPart> parts = new ArrayList<>(enrichments.size() + postProcessors.size());
        parts.addAll(List.copyOf(enrichments));
        parts.addAll(List.copyOf(postProcessors));
        return new AggregationPartGraph(parts);
    }

    List<String> unknownNames(Set<String> names) {
        return names.stream().filter(name -> !partsByName.containsKey(name)).toList();
    }

    AggregationPartSelection expandDependencies(AggregationPartSelection partSelection) {
        if (partSelection.all()) {
            return partSelection;
        }

        Set<String> effectiveNames = new LinkedHashSet<>(partSelection.names());
        boolean changed;
        do {
            changed = false;
            for (AggregationPart part : parts) {
                if (effectiveNames.contains(part.name())) {
                    changed = effectiveNames.addAll(part.dependencies()) || changed;
                }
            }
        } while (changed);
        return AggregationPartSelection.subset(effectiveNames);
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

    private static Map<String, AggregationPart> buildPartsIndex(List<AggregationPart> registeredParts) {
        Map<String, AggregationPart> index = new LinkedHashMap<>();
        registeredParts.forEach(part -> addUnique(index, part.name(), part));
        return Map.copyOf(index);
    }

    private static void validateDependencies(
            List<AggregationPart> registeredParts, Map<String, AggregationPart> partsByName) {
        for (AggregationPart part : registeredParts) {
            List<String> unknownDependencies = part.dependencies().stream()
                    .filter(dependency -> !partsByName.containsKey(dependency))
                    .toList();
            if (!unknownDependencies.isEmpty()) {
                throw new IllegalStateException("Unknown aggregation component dependency for " + part.name() + ": "
                        + String.join(", ", unknownDependencies));
            }
        }
    }

    private static List<AggregationPart> orderByDependencies(
            List<AggregationPart> registeredParts, Map<String, AggregationPart> partsByName) {
        List<AggregationPart> ordered = new ArrayList<>(registeredParts.size());
        Set<String> visiting = new HashSet<>();
        Set<String> visited = new HashSet<>();

        for (AggregationPart part : registeredParts) {
            appendWithDependencies(part, partsByName, visiting, visited, ordered);
        }

        return List.copyOf(ordered);
    }

    private static void appendWithDependencies(
            AggregationPart part,
            Map<String, AggregationPart> partsByName,
            Set<String> visiting,
            Set<String> visited,
            List<AggregationPart> ordered) {
        if (visited.contains(part.name())) {
            return;
        }
        if (!visiting.add(part.name())) {
            throw new IllegalStateException("Cyclic aggregation component dependency involving " + part.name());
        }

        for (String dependency : part.dependencies()) {
            appendWithDependencies(partByName(partsByName, dependency), partsByName, visiting, visited, ordered);
        }

        visiting.remove(part.name());
        visited.add(part.name());
        ordered.add(part);
    }

    private static AggregationPart partByName(Map<String, AggregationPart> partsByName, String name) {
        AggregationPart part = partsByName.get(name);
        if (part == null) {
            throw new IllegalStateException("Unknown aggregation component dependency: " + name);
        }
        return part;
    }

    private int depth(AggregationPart part, AggregationPartSelection effectiveSelection, Map<String, Integer> depths) {
        Integer existing = depths.get(part.name());
        if (existing != null) {
            return existing;
        }

        int depth = part.dependencies().stream()
                .filter(effectiveSelection::includes)
                .map(dependency -> partByName(partsByName, dependency))
                .mapToInt(dependency -> depth(dependency, effectiveSelection, depths) + 1)
                .max()
                .orElse(0);
        depths.put(part.name(), depth);
        return depth;
    }

    private static void addUnique(Map<String, AggregationPart> index, String name, AggregationPart owner) {
        AggregationPart previous = index.putIfAbsent(name, owner);
        if (previous != null) {
            throw new IllegalStateException("Duplicate aggregation component name: " + name);
        }
    }
}
