package dev.abramenka.aggregation.service;

import dev.abramenka.aggregation.enrichment.AggregationEnrichment;
import dev.abramenka.aggregation.error.UnsupportedAggregationEnrichmentException;
import dev.abramenka.aggregation.model.AggregationPart;
import dev.abramenka.aggregation.model.EnrichmentSelection;
import dev.abramenka.aggregation.postprocessor.AggregationPostProcessor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Component
class AggregationPartPlanner {

    private final List<AggregationEnrichment> enrichments;
    private final List<AggregationPostProcessor> postProcessors;
    private final List<AggregationPart> parts;
    private final Set<String> knownNames;

    AggregationPartPlanner(List<AggregationEnrichment> enrichments, List<AggregationPostProcessor> postProcessors) {
        this.enrichments = List.copyOf(enrichments);
        this.postProcessors = List.copyOf(postProcessors);
        this.parts = parts(this.enrichments, this.postProcessors);
        this.knownNames = buildKnownNamesIndex(this.parts);
        validateDependencies(this.parts, this.knownNames);
    }

    AggregationPartPlan plan(@Nullable List<String> include) {
        EnrichmentSelection requestedSelection = EnrichmentSelection.from(include);
        validateSelection(requestedSelection);
        EnrichmentSelection effectiveSelection = expandDependencies(requestedSelection);
        return new AggregationPartPlan(
                requestedSelection,
                effectiveSelection,
                selectedEnrichments(effectiveSelection),
                selectedPostProcessors(effectiveSelection));
    }

    private List<AggregationEnrichment> selectedEnrichments(EnrichmentSelection effectiveSelection) {
        return enrichments.stream()
                .filter(enrichment -> effectiveSelection.includes(enrichment.name()))
                .toList();
    }

    private List<AggregationPostProcessor> selectedPostProcessors(EnrichmentSelection effectiveSelection) {
        return postProcessors.stream()
                .filter(postProcessor -> effectiveSelection.includes(postProcessor.name()))
                .toList();
    }

    private void validateSelection(EnrichmentSelection enrichmentSelection) {
        if (enrichmentSelection.all()) {
            return;
        }

        List<String> unknownParts = enrichmentSelection.names().stream()
                .filter(name -> !knownNames.contains(name))
                .toList();

        if (!unknownParts.isEmpty()) {
            throw new UnsupportedAggregationEnrichmentException(unknownParts);
        }
    }

    private EnrichmentSelection expandDependencies(EnrichmentSelection enrichmentSelection) {
        if (enrichmentSelection.all()) {
            return enrichmentSelection;
        }

        Set<String> effectiveNames = new LinkedHashSet<>(enrichmentSelection.names());
        boolean changed;
        do {
            changed = false;
            for (AggregationPart part : parts) {
                if (effectiveNames.contains(part.name())) {
                    changed = effectiveNames.addAll(part.dependencies()) || changed;
                }
            }
        } while (changed);
        return EnrichmentSelection.subset(effectiveNames);
    }

    private static List<AggregationPart> parts(
            List<AggregationEnrichment> registeredEnrichments,
            List<AggregationPostProcessor> registeredPostProcessors) {
        List<AggregationPart> parts = new ArrayList<>(registeredEnrichments.size() + registeredPostProcessors.size());
        parts.addAll(registeredEnrichments);
        parts.addAll(registeredPostProcessors);
        return List.copyOf(parts);
    }

    private static Set<String> buildKnownNamesIndex(List<AggregationPart> registeredParts) {
        Set<String> names = new LinkedHashSet<>();
        Map<String, Object> seen = new LinkedHashMap<>();
        registeredParts.forEach(part -> addUnique(seen, names, part.name(), part));
        return Set.copyOf(names);
    }

    private static void validateDependencies(List<AggregationPart> registeredParts, Set<String> knownNames) {
        for (AggregationPart part : registeredParts) {
            List<String> unknownDependencies = part.dependencies().stream()
                    .filter(dependency -> !knownNames.contains(dependency))
                    .toList();
            if (!unknownDependencies.isEmpty()) {
                throw new IllegalStateException("Unknown aggregation component dependency for " + part.name() + ": "
                        + String.join(", ", unknownDependencies));
            }
        }
    }

    private static void addUnique(Map<String, Object> seen, Set<String> names, String name, Object owner) {
        Object previous = seen.putIfAbsent(name, owner);
        if (previous != null) {
            throw new IllegalStateException("Duplicate aggregation component name: " + name);
        }
        names.add(name);
    }
}
