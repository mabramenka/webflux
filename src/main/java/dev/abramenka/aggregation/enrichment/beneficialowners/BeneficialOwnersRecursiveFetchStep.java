package dev.abramenka.aggregation.enrichment.beneficialowners;

import dev.abramenka.aggregation.client.DownstreamClientResponses;
import dev.abramenka.aggregation.client.HttpServiceGroups;
import dev.abramenka.aggregation.client.Owners;
import dev.abramenka.aggregation.error.EnrichmentDependencyException;
import dev.abramenka.aggregation.error.FacadeException;
import dev.abramenka.aggregation.model.AggregationContext;
import dev.abramenka.aggregation.workflow.StepResult;
import dev.abramenka.aggregation.workflow.WorkflowContext;
import dev.abramenka.aggregation.workflow.WorkflowStep;
import dev.abramenka.aggregation.workflow.recursive.CyclePolicy;
import dev.abramenka.aggregation.workflow.recursive.RecursiveTraversalEngine;
import dev.abramenka.aggregation.workflow.recursive.TraversalGroupResult;
import dev.abramenka.aggregation.workflow.recursive.TraversalPolicy;
import dev.abramenka.aggregation.workflow.recursive.TraversalResult;
import dev.abramenka.aggregation.workflow.recursive.TraversalSeedGroup;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Beneficial-owners local adapter over {@link RecursiveFetchStep} that keeps traversal error
 * mapping aligned with legacy behavior.
 */
final class BeneficialOwnersRecursiveFetchStep implements WorkflowStep {

    private static final String CLIENT_NAME = HttpServiceGroups.downstreamClientName(HttpServiceGroups.OWNERS);
    private static final String TREE_METRIC = "aggregation.beneficial_owners.tree";
    private static final String SUCCESS = "success";
    private static final String FAILURE = "failure";

    private final String name;
    private final String storeAs;
    private final Owners ownersClient;
    private final ObjectMapper objectMapper;
    private final RootEntityTargets rootEntityTargets;
    private final MeterRegistry meterRegistry;
    private final RecursiveTraversalEngine traversalEngine = new RecursiveTraversalEngine();
    private final TraversalPolicy policy =
            new TraversalPolicy(OwnershipResolver.MAX_DEPTH, CyclePolicy.SKIP_VISITED, true);

    BeneficialOwnersRecursiveFetchStep(
            String name,
            String storeAs,
            Owners ownersClient,
            ObjectMapper objectMapper,
            RootEntityTargets rootEntityTargets,
            MeterRegistry meterRegistry) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("BeneficialOwnersRecursiveFetchStep name must not be blank");
        }
        if (storeAs == null || storeAs.isBlank()) {
            throw new IllegalArgumentException("BeneficialOwnersRecursiveFetchStep storeAs must not be blank");
        }
        this.name = name;
        this.storeAs = storeAs;
        this.ownersClient = Objects.requireNonNull(ownersClient, "ownersClient");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.rootEntityTargets = Objects.requireNonNull(rootEntityTargets, "rootEntityTargets");
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Mono<StepResult> execute(WorkflowContext context) {
        List<TraversalSeedGroup> seedGroups = extractSeedGroups(context.rootSnapshot());
        return traverseGroups(seedGroups, context.aggregationContext())
                .map(result -> StepResult.stored(storeAs, result.toJsonNode(JsonNodeFactory.instance)))
                .onErrorMap(this::mapLegacyEquivalentError);
    }

    private Mono<TraversalResult> traverseGroups(List<TraversalSeedGroup> seedGroups, AggregationContext context) {
        if (seedGroups.isEmpty()) {
            return Mono.just(new TraversalResult(List.of()));
        }
        return Flux.fromIterable(seedGroups)
                .concatMap(seedGroup -> traverseSingleGroup(seedGroup, context))
                .collectList()
                .map(TraversalResult::new);
    }

    private Mono<TraversalGroupResult> traverseSingleGroup(TraversalSeedGroup seedGroup, AggregationContext context) {
        return traversalEngine
                .traverse(
                        List.of(seedGroup),
                        policy,
                        keys -> fetchBatch(keys, context),
                        EntityNumbersExtractor::childNumbers,
                        EntityNumbersExtractor::isIndividual)
                .map(result -> result.groups().get(0))
                .doOnSuccess(group -> recordTree(SUCCESS))
                .doOnError(error -> recordTree(FAILURE));
    }

    private Throwable mapLegacyEquivalentError(Throwable error) {
        if (error instanceof FacadeException) {
            return error;
        }
        if (error instanceof RecursiveTraversalEngine.TraversalException traversalException) {
            return switch (traversalException.reason()) {
                case DEPTH_EXCEEDED, CONTRACT_VIOLATION ->
                    EnrichmentDependencyException.contractViolation(
                            BeneficialOwnersEnrichment.NAME, traversalException);
            };
        }
        return error;
    }

    private List<TraversalSeedGroup> extractSeedGroups(JsonNode source) {
        List<RootEntityTarget> targets = rootEntityTargets.collect(source);
        List<TraversalSeedGroup> groups = new ArrayList<>(targets.size());
        for (RootEntityTarget target : targets) {
            ObjectNode metadata = JsonNodeFactory.instance.objectNode();
            metadata.put("dataIndex", target.dataIndex());
            metadata.put("ownerIndex", target.ownerIndex());
            groups.add(new TraversalSeedGroup(
                    metadata, new ArrayList<>(EntityNumbersExtractor.childNumbers(target.node()))));
        }
        return groups;
    }

    private Mono<Map<String, JsonNode>> fetchBatch(Set<String> numbers, AggregationContext context) {
        ObjectNode request = objectMapper.createObjectNode();
        ArrayNode ids = request.putArray("ids");
        numbers.forEach(ids::add);
        return DownstreamClientResponses.requireBody(
                        CLIENT_NAME,
                        ownersClient.fetchOwners(request, Owners.DEFAULT_FIELDS, context.clientRequestContext()))
                .map(this::indexByNumber);
    }

    private Map<String, JsonNode> indexByNumber(JsonNode response) {
        JsonNode data = response.path("data");
        if (!data.isArray()) {
            throw EnrichmentDependencyException.contractViolation(
                    BeneficialOwnersEnrichment.NAME,
                    new IllegalStateException("owners response is missing the 'data' array"));
        }
        Map<String, JsonNode> out = new LinkedHashMap<>();
        for (JsonNode item : data.values()) {
            String number = EntityNumbersExtractor.ownerNumber(item);
            if (!number.isBlank()) {
                out.putIfAbsent(number, item);
            }
        }
        return out;
    }

    private void recordTree(String outcome) {
        meterRegistry.counter(TREE_METRIC, "outcome", outcome).increment();
    }
}
