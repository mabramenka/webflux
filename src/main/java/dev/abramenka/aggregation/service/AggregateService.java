package dev.abramenka.aggregation.service;

import dev.abramenka.aggregation.api.AggregateRequest;
import dev.abramenka.aggregation.client.AccountGroups;
import dev.abramenka.aggregation.enrichment.AggregationEnrichment;
import dev.abramenka.aggregation.error.DownstreamClientException;
import dev.abramenka.aggregation.error.UnsupportedAggregationEnrichmentException;
import dev.abramenka.aggregation.model.AggregationContext;
import dev.abramenka.aggregation.model.ClientRequestContext;
import dev.abramenka.aggregation.model.EnrichmentSelection;
import dev.abramenka.aggregation.postprocessor.AggregationPostProcessor;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Service
public class AggregateService {

    private static final String ACCOUNT_GROUP_CLIENT_NAME = "Account group";
    private static final String IDS_FIELD = "ids";

    private final AccountGroups accountGroupClient;
    private final List<AggregationEnrichment> enrichments;
    private final List<AggregationPostProcessor> postProcessors;
    private final Set<String> knownNames;
    private final ObservationRegistry observationRegistry;
    private final EnrichmentExecutor enrichmentExecutor;
    private final AggregationMerger aggregationMerger;
    private final ObjectMapper objectMapper;

    public AggregateService(
            AccountGroups accountGroupClient,
            List<AggregationEnrichment> enrichments,
            List<AggregationPostProcessor> postProcessors,
            ObservationRegistry observationRegistry,
            EnrichmentExecutor enrichmentExecutor,
            AggregationMerger aggregationMerger,
            ObjectMapper objectMapper) {
        this.accountGroupClient = accountGroupClient;
        this.enrichments = List.copyOf(enrichments);
        this.postProcessors = List.copyOf(postProcessors);
        this.knownNames = buildKnownNamesIndex(enrichments, postProcessors);
        validateDependencies(this.postProcessors, this.knownNames);
        this.observationRegistry = observationRegistry;
        this.enrichmentExecutor = enrichmentExecutor;
        this.aggregationMerger = aggregationMerger;
        this.objectMapper = objectMapper;
    }

    public Mono<JsonNode> aggregate(AggregateRequest request, ClientRequestContext clientRequestContext) {
        return Mono.defer(() -> {
            Observation observation = Observation.start("aggregation.request", observationRegistry);
            EnrichmentSelection requestedSelection = EnrichmentSelection.from(request.include());
            validateEnrichmentSelection(requestedSelection);
            EnrichmentSelection effectiveSelection = expandDependencies(requestedSelection);
            observation.lowCardinalityKeyValue("enrichment_selection", requestedSelection.all() ? "all" : "subset");
            observation.lowCardinalityKeyValue(
                    "requested_enrichments",
                    Integer.toString(requestedSelection.names().size()));

            ObjectNode accountGroupRequest = toAccountGroupRequest(request.ids());

            return accountGroupClient
                    .fetchAccountGroup(accountGroupRequest, clientRequestContext)
                    // Catches DecodingException and other codec failures that surface after the
                    // WebClient filter chain; DownstreamClientErrorFilter only sees HTTP-layer errors.
                    .onErrorMap(
                            ex -> !(ex instanceof DownstreamClientException),
                            ex -> DownstreamClientException.transport(ACCOUNT_GROUP_CLIENT_NAME, ex))
                    .flatMap(accountGroupResponse -> {
                        AggregationContext context =
                                new AggregationContext(accountGroupResponse, clientRequestContext, effectiveSelection);

                        List<AggregationEnrichment> enabledEnrichments = enrichments.stream()
                                .filter(enrichment -> effectiveSelection.includes(enrichment.name()))
                                .filter(enrichment -> enrichment.supports(context))
                                .toList();

                        ObjectNode root =
                                aggregationMerger.mutableRoot(ACCOUNT_GROUP_CLIENT_NAME, accountGroupResponse);

                        int concurrency = Math.max(1, enabledEnrichments.size());
                        List<AggregationPostProcessor> enabledPostProcessors = postProcessors.stream()
                                .filter(pp -> effectiveSelection.includes(pp.name()))
                                .toList();
                        return Flux.fromIterable(enabledEnrichments)
                                .flatMap(enrichment -> enrichmentExecutor.fetch(enrichment, context), concurrency)
                                .collectList()
                                .map(results -> aggregationMerger.merge(root, enabledEnrichments, results))
                                .flatMap(merged -> runPostProcessors(enabledPostProcessors, root, context)
                                        .thenReturn(merged));
                    })
                    .contextWrite(context -> context.put(ObservationThreadLocalAccessor.KEY, observation))
                    .doOnError(observation::error)
                    .doFinally(signalType -> observation.stop());
        });
    }

    private ObjectNode toAccountGroupRequest(List<String> ids) {
        ObjectNode request = objectMapper.createObjectNode();
        ArrayNode idsArray = request.putArray(IDS_FIELD);
        ids.forEach(id -> idsArray.add(id.toUpperCase(Locale.ROOT)));
        return request;
    }

    private void validateEnrichmentSelection(EnrichmentSelection enrichmentSelection) {
        if (enrichmentSelection.all()) {
            return;
        }

        List<String> unknownEnrichments = enrichmentSelection.names().stream()
                .filter(name -> !knownNames.contains(name))
                .toList();

        if (!unknownEnrichments.isEmpty()) {
            throw new UnsupportedAggregationEnrichmentException(unknownEnrichments);
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
            for (AggregationPostProcessor postProcessor : postProcessors) {
                if (effectiveNames.contains(postProcessor.name())) {
                    changed = effectiveNames.addAll(postProcessor.dependencies()) || changed;
                }
            }
        } while (changed);
        return EnrichmentSelection.subset(effectiveNames);
    }

    private Mono<Void> runPostProcessors(
            List<AggregationPostProcessor> enabledPostProcessors, ObjectNode root, AggregationContext context) {
        if (enabledPostProcessors.isEmpty()) {
            return Mono.empty();
        }
        return Flux.fromIterable(enabledPostProcessors)
                .concatMap(postProcessor -> postProcessor.apply(root, context).onErrorResume(ex -> Mono.empty()))
                .then();
    }

    private static Set<String> buildKnownNamesIndex(
            List<AggregationEnrichment> registeredEnrichments,
            List<AggregationPostProcessor> registeredPostProcessors) {
        Set<String> names = new LinkedHashSet<>();
        Map<String, Object> seen = new LinkedHashMap<>();
        registeredEnrichments.forEach(enrichment -> addUnique(seen, names, enrichment.name(), enrichment));
        registeredPostProcessors.forEach(postProcessor -> addUnique(seen, names, postProcessor.name(), postProcessor));
        return Set.copyOf(names);
    }

    private static void validateDependencies(
            List<AggregationPostProcessor> registeredPostProcessors, Set<String> knownNames) {
        for (AggregationPostProcessor postProcessor : registeredPostProcessors) {
            List<String> unknownDependencies = postProcessor.dependencies().stream()
                    .filter(dependency -> !knownNames.contains(dependency))
                    .toList();
            if (!unknownDependencies.isEmpty()) {
                throw new IllegalStateException("Unknown aggregation component dependency for " + postProcessor.name()
                        + ": " + String.join(", ", unknownDependencies));
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
