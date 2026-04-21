package dev.abramenka.aggregation.postprocessor;

import dev.abramenka.aggregation.model.AggregationContext;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Component
@Slf4j
class BeneficialOwnersPostProcessor implements AggregationPostProcessor {

    static final String NAME = "beneficialOwners";
    private static final String TARGET_FIELD = "beneficialOwnersDetails";
    private static final String TREE_METRIC = "aggregation.beneficial_owners.tree";
    private static final String PHASE_METRIC = "aggregation.enrichment.requests";

    private final OwnershipResolver resolver;
    private final MeterRegistry meterRegistry;

    BeneficialOwnersPostProcessor(OwnershipResolver resolver, MeterRegistry meterRegistry) {
        this.resolver = resolver;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Mono<Void> apply(ObjectNode root, AggregationContext context) {
        List<ObjectNode> rootEntities = collectRootEntities(root);
        if (rootEntities.isEmpty()) {
            recordPhase("success");
            return Mono.empty();
        }
        int concurrency = Math.max(1, rootEntities.size());
        return Flux.fromIterable(rootEntities)
                .flatMap(entityNode -> resolveOne(entityNode, context), concurrency)
                .collectList()
                .doOnNext(pairs -> pairs.forEach(pair -> pair.entity.set(TARGET_FIELD, pair.array)))
                .then()
                .doOnSuccess(unused -> recordPhase("success"))
                .doOnError(ex -> recordPhase("failure"));
    }

    private Mono<ResolvedEntity> resolveOne(ObjectNode entityNode, AggregationContext context) {
        return resolver.resolveTree(entityNode, context)
                .doOnSuccess(array -> recordTree("success"))
                .onErrorResume(ex -> {
                    recordTree("failure");
                    log.warn(
                            "Beneficial-owners resolution for a root entity failed and will be skipped: {}",
                            ex.getMessage());
                    return Mono.empty();
                })
                .map(array -> new ResolvedEntity(entityNode, array));
    }

    private static List<ObjectNode> collectRootEntities(ObjectNode root) {
        JsonNode dataArray = root.path("data");
        if (!dataArray.isArray()) {
            return List.of();
        }
        List<ObjectNode> entities = new ArrayList<>();
        for (JsonNode item : dataArray.values()) {
            JsonNode ownersArray = item.path("owners1");
            if (!ownersArray.isArray()) {
                continue;
            }
            for (JsonNode owner : ownersArray.values()) {
                if (owner instanceof ObjectNode ownerObject && EntityNumbersExtractor.isEntity(ownerObject)) {
                    entities.add(ownerObject);
                }
            }
        }
        return entities;
    }

    private void recordTree(String outcome) {
        meterRegistry.counter(TREE_METRIC, "outcome", outcome).increment();
    }

    private void recordPhase(String outcome) {
        meterRegistry
                .counter(PHASE_METRIC, "enrichment", NAME, "outcome", outcome)
                .increment();
    }

    private record ResolvedEntity(ObjectNode entity, ArrayNode array) {}
}
