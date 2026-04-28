package dev.abramenka.aggregation.enrichment.beneficialowners;

import dev.abramenka.aggregation.client.DownstreamClientResponses;
import dev.abramenka.aggregation.client.HttpServiceGroups;
import dev.abramenka.aggregation.client.Owners;
import dev.abramenka.aggregation.model.AggregationContext;
import dev.abramenka.aggregation.workflow.recursive.CyclePolicy;
import dev.abramenka.aggregation.workflow.recursive.RecursiveTraversalEngine;
import dev.abramenka.aggregation.workflow.recursive.TraversalPolicy;
import dev.abramenka.aggregation.workflow.recursive.TraversalResult;
import dev.abramenka.aggregation.workflow.recursive.TraversalSeedGroup;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** Beneficial-owners recursive fetch algorithm over the owners downstream service. */
final class BeneficialOwnersRecursiveFetcher {

    private static final String CLIENT_NAME = HttpServiceGroups.downstreamClientName(HttpServiceGroups.OWNERS);
    private static final int MAX_DEPTH = 6;

    private final Owners ownersClient;
    private final ObjectMapper objectMapper;
    private final RootEntityTargets rootEntityTargets;
    private final RecursiveTraversalEngine traversalEngine = new RecursiveTraversalEngine();
    private final TraversalPolicy policy = new TraversalPolicy(MAX_DEPTH, CyclePolicy.SKIP_VISITED, true);

    BeneficialOwnersRecursiveFetcher(
            Owners ownersClient, ObjectMapper objectMapper, RootEntityTargets rootEntityTargets) {
        this.ownersClient = Objects.requireNonNull(ownersClient, "ownersClient");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.rootEntityTargets = Objects.requireNonNull(rootEntityTargets, "rootEntityTargets");
    }

    Mono<TraversalResult> fetch(JsonNode source, AggregationContext context) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(context, "context");
        return traversalEngine.traverse(
                extractSeedGroups(source),
                policy,
                keys -> fetchBatch(keys, context),
                EntityNumbersExtractor::childNumbers,
                EntityNumbersExtractor::isIndividual);
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
            throw BeneficialOwnersRecursiveFetchException.contractViolation(
                    "owners response is missing the 'data' array");
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
}

final class BeneficialOwnersRecursiveFetchException extends RuntimeException {

    private BeneficialOwnersRecursiveFetchException(String message) {
        super(message);
    }

    static BeneficialOwnersRecursiveFetchException contractViolation(String message) {
        return new BeneficialOwnersRecursiveFetchException(message);
    }
}
