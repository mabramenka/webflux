package dev.abramenka.aggregation.part;

import dev.abramenka.aggregation.model.AggregationPart;
import dev.abramenka.aggregation.model.AggregationPartSelection;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class AggregationPartGraph {

    private final List<AggregationPart> parts;
    private final Map<String, AggregationPart> partsByName;
    private final String basePartName;
    private final Set<String> publicPartNames;
    private final AggregationPartLevelPlanner levelPlanner;

    private AggregationPartGraph(List<AggregationPart> parts) {
        this.parts = List.copyOf(parts);
        this.partsByName = buildPartsIndex(this.parts);
        this.basePartName = findAndValidateBasePart(this.parts);
        this.publicPartNames = findPublicPartNames(this.parts);
        validateDependencies(this.parts, this.partsByName);
        List<AggregationPart> orderedParts = orderByDependencies(this.parts, this.partsByName);
        this.levelPlanner = new AggregationPartLevelPlanner(orderedParts, this.partsByName);
    }

    static AggregationPartGraph from(List<AggregationPart> registeredParts) {
        List<AggregationPart> parts = new ArrayList<>(registeredParts);
        return new AggregationPartGraph(parts);
    }

    List<String> unknownNames(Set<String> names) {
        return names.stream().filter(name -> !partsByName.containsKey(name)).toList();
    }

    Set<String> publicPartNames() {
        return publicPartNames;
    }

    String basePartName() {
        return basePartName;
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

    AggregationPartSelection includeBase(AggregationPartSelection partSelection) {
        if (partSelection.all()) {
            return partSelection;
        }
        Set<String> effectiveNames = new LinkedHashSet<>(partSelection.names());
        effectiveNames.add(basePartName);
        return AggregationPartSelection.subset(effectiveNames);
    }

    List<List<AggregationPart>> selectedLevels(AggregationPartSelection effectiveSelection) {
        return levelPlanner.selectedLevels(effectiveSelection);
    }

    private static Map<String, AggregationPart> buildPartsIndex(List<AggregationPart> registeredParts) {
        Map<String, AggregationPart> index = new LinkedHashMap<>();
        registeredParts.forEach(part -> addUnique(index, part.name(), part));
        return Map.copyOf(index);
    }

    private static String findAndValidateBasePart(List<AggregationPart> registeredParts) {
        List<AggregationPart> baseParts =
                registeredParts.stream().filter(AggregationPart::base).toList();
        if (baseParts.size() != 1) {
            throw new IllegalStateException("Exactly one base aggregation part is required");
        }
        AggregationPart basePart = baseParts.getFirst();
        if (!basePart.dependencies().isEmpty()) {
            throw new IllegalStateException(
                    "Base aggregation part '" + basePart.name() + "' must not declare dependencies");
        }
        return basePart.name();
    }

    private static Set<String> findPublicPartNames(List<AggregationPart> registeredParts) {
        Set<String> names = new LinkedHashSet<>();
        for (AggregationPart part : registeredParts) {
            if (part.publicSelectable()) {
                names.add(part.name());
            }
        }
        return Set.copyOf(names);
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

    private static void addUnique(Map<String, AggregationPart> index, String name, AggregationPart owner) {
        AggregationPart previous = index.putIfAbsent(name, owner);
        if (previous != null) {
            throw new IllegalStateException("Duplicate aggregation component name: " + name);
        }
    }
}
