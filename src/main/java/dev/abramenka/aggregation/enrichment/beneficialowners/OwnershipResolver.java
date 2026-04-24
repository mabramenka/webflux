package dev.abramenka.aggregation.enrichment.beneficialowners;

import dev.abramenka.aggregation.client.DownstreamClientResponses;
import dev.abramenka.aggregation.client.HttpServiceGroups;
import dev.abramenka.aggregation.client.Owners;
import dev.abramenka.aggregation.model.AggregationContext;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Component
class OwnershipResolver {

    private static final String CLIENT_NAME = HttpServiceGroups.downstreamClientName(HttpServiceGroups.OWNERS);
    static final int MAX_DEPTH = 6;
    private static final String IDS_FIELD = "ids";

    private final Owners ownersClient;
    private final ObjectMapper objectMapper;

    OwnershipResolver(Owners ownersClient, ObjectMapper objectMapper) {
        this.ownersClient = ownersClient;
        this.objectMapper = objectMapper;
    }

    Mono<ArrayNode> resolveTree(JsonNode rootEntity, AggregationContext context) {
        Map<String, JsonNode> resolved = new HashMap<>();
        Map<String, JsonNode> individuals = new LinkedHashMap<>();
        Set<String> initialSeeds = EntityNumbersExtractor.childNumbers(rootEntity);
        return resolveLevel(initialSeeds, resolved, individuals, 1, context).then(Mono.fromSupplier(() -> {
            ArrayNode arr = objectMapper.createArrayNode();
            individuals.values().forEach(arr::add);
            return arr;
        }));
    }

    private Mono<Void> resolveLevel(
            Set<String> seeds,
            Map<String, JsonNode> resolved,
            Map<String, JsonNode> individuals,
            int depth,
            AggregationContext context) {
        if (seeds.isEmpty()) {
            return Mono.empty();
        }
        if (depth > MAX_DEPTH) {
            return Mono.error(new BeneficialOwnersResolutionException(
                    BeneficialOwnersResolutionException.Reason.DEPTH_EXCEEDED,
                    "beneficial-owners tree exceeds max depth " + MAX_DEPTH));
        }
        Set<String> toResolve = new LinkedHashSet<>(seeds);
        toResolve.removeAll(resolved.keySet());
        if (toResolve.isEmpty()) {
            return Mono.empty();
        }
        return fetchBatch(toResolve, context).flatMap(responseByNumber -> {
            Set<String> nextSeeds = new LinkedHashSet<>();
            for (String number : toResolve) {
                JsonNode ownerNode = responseByNumber.get(number);
                if (ownerNode == null) {
                    continue;
                }
                resolved.put(number, ownerNode);
                if (EntityNumbersExtractor.isIndividual(ownerNode)) {
                    individuals.putIfAbsent(number, ownerNode);
                } else if (EntityNumbersExtractor.isEntity(ownerNode)) {
                    nextSeeds.addAll(EntityNumbersExtractor.childNumbers(ownerNode));
                }
            }
            return resolveLevel(nextSeeds, resolved, individuals, depth + 1, context);
        });
    }

    private Mono<Map<String, JsonNode>> fetchBatch(Set<String> numbers, AggregationContext context) {
        ObjectNode request = objectMapper.createObjectNode();
        ArrayNode ids = request.putArray(IDS_FIELD);
        numbers.forEach(ids::add);
        return DownstreamClientResponses.requireBody(
                        CLIENT_NAME,
                        ownersClient.fetchOwners(request, Owners.DEFAULT_FIELDS, context.clientRequestContext()))
                .map(this::indexByNumber)
                .map(responseByNumber -> requireAllRequestedNumbers(numbers, responseByNumber))
                .onErrorMap(
                        ex -> !(ex instanceof BeneficialOwnersResolutionException),
                        ex -> new BeneficialOwnersResolutionException(
                                BeneficialOwnersResolutionException.Reason.DOWNSTREAM_FAILED,
                                "owners client failed while resolving beneficial owners",
                                ex));
    }

    private Map<String, JsonNode> requireAllRequestedNumbers(
            Set<String> numbers, Map<String, JsonNode> responseByNumber) {
        for (String number : numbers) {
            if (!responseByNumber.containsKey(number)) {
                throw new BeneficialOwnersResolutionException(
                        BeneficialOwnersResolutionException.Reason.MALFORMED_RESPONSE,
                        "owners response is missing a requested owner");
            }
        }
        return responseByNumber;
    }

    private Map<String, JsonNode> indexByNumber(JsonNode response) {
        JsonNode data = response.path("data");
        if (!data.isArray()) {
            throw new BeneficialOwnersResolutionException(
                    BeneficialOwnersResolutionException.Reason.MALFORMED_RESPONSE,
                    "owners response is missing the 'data' array");
        }
        Map<String, JsonNode> out = new HashMap<>();
        for (JsonNode item : data.values()) {
            String number = EntityNumbersExtractor.ownerNumber(item);
            if (!number.isBlank()) {
                out.putIfAbsent(number, item);
            }
        }
        return out;
    }
}
