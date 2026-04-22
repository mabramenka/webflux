package dev.abramenka.aggregation.enrichment.beneficialowners;

import dev.abramenka.aggregation.model.AggregationContext;
import dev.abramenka.aggregation.model.AggregationEnrichment;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Component
@Slf4j
class BeneficialOwnersEnrichment implements AggregationEnrichment {

    static final String NAME = "beneficialOwners";
    private static final String TARGET_FIELD = "beneficialOwnersDetails";
    private static final String TREE_METRIC = "aggregation.beneficial_owners.tree";
    private static final String DATA_FIELD = "data";
    private static final String DATA_INDEX_FIELD = "dataIndex";
    private static final String OWNER_INDEX_FIELD = "ownerIndex";
    private static final String DETAILS_FIELD = "details";

    private final OwnershipResolver resolver;
    private final MeterRegistry meterRegistry;
    private final ObjectMapper objectMapper;

    BeneficialOwnersEnrichment(OwnershipResolver resolver, MeterRegistry meterRegistry, ObjectMapper objectMapper) {
        this.resolver = resolver;
        this.meterRegistry = meterRegistry;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Set<String> dependencies() {
        return Set.of("owners");
    }

    @Override
    public Mono<JsonNode> fetch(AggregationContext context) {
        List<RootEntityTarget> rootEntities = collectRootEntities(context.accountGroupResponse());
        int concurrency = Math.max(1, rootEntities.size());
        return Flux.fromIterable(rootEntities)
                .flatMap(entity -> resolveOne(entity, context), concurrency)
                .collectList()
                .map(this::response);
    }

    @Override
    public void merge(ObjectNode root, JsonNode enrichmentResponse) {
        JsonNode data = enrichmentResponse.path(DATA_FIELD);
        if (!data.isArray()) {
            return;
        }
        for (JsonNode item : data.values()) {
            int dataIndex = item.path(DATA_INDEX_FIELD).asInt(-1);
            int ownerIndex = item.path(OWNER_INDEX_FIELD).asInt(-1);
            JsonNode details = item.path(DETAILS_FIELD);
            JsonNode owner =
                    root.path(DATA_FIELD).path(dataIndex).path("owners1").path(ownerIndex);
            if (owner instanceof ObjectNode ownerObject && details.isArray()) {
                ownerObject.set(TARGET_FIELD, details.deepCopy());
            }
        }
    }

    private Mono<ResolvedEntity> resolveOne(RootEntityTarget entity, AggregationContext context) {
        return resolver.resolveTree(entity.node(), context)
                .doOnSuccess(array -> recordTree("success"))
                .onErrorResume(ex -> {
                    recordTree("failure");
                    log.warn(
                            "Beneficial-owners resolution for a root entity failed and will be skipped: {}",
                            ex.getMessage());
                    return Mono.empty();
                })
                .map(array -> new ResolvedEntity(entity.dataIndex(), entity.ownerIndex(), array));
    }

    private ObjectNode response(List<ResolvedEntity> resolvedEntities) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode data = response.putArray(DATA_FIELD);
        for (ResolvedEntity entity : resolvedEntities) {
            ObjectNode item = data.addObject();
            item.put(DATA_INDEX_FIELD, entity.dataIndex());
            item.put(OWNER_INDEX_FIELD, entity.ownerIndex());
            item.set(DETAILS_FIELD, entity.details());
        }
        return response;
    }

    private static List<RootEntityTarget> collectRootEntities(JsonNode root) {
        JsonNode dataArray = root.path(DATA_FIELD);
        if (!dataArray.isArray()) {
            return List.of();
        }
        List<RootEntityTarget> entities = new ArrayList<>();
        int dataIndex = 0;
        for (JsonNode item : dataArray.values()) {
            JsonNode ownersArray = item.path("owners1");
            if (!ownersArray.isArray()) {
                dataIndex++;
                continue;
            }
            int ownerIndex = 0;
            for (JsonNode owner : ownersArray.values()) {
                if (owner instanceof ObjectNode ownerObject && EntityNumbersExtractor.isEntity(ownerObject)) {
                    entities.add(new RootEntityTarget(dataIndex, ownerIndex, ownerObject));
                }
                ownerIndex++;
            }
            dataIndex++;
        }
        return entities;
    }

    private void recordTree(String outcome) {
        meterRegistry.counter(TREE_METRIC, "outcome", outcome).increment();
    }

    private record RootEntityTarget(int dataIndex, int ownerIndex, ObjectNode node) {}

    private record ResolvedEntity(int dataIndex, int ownerIndex, ArrayNode details) {}
}
