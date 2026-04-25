package dev.abramenka.aggregation.enrichment.beneficialowners;

import dev.abramenka.aggregation.error.EnrichmentDependencyException;
import dev.abramenka.aggregation.error.FacadeException;
import dev.abramenka.aggregation.model.AggregationContext;
import dev.abramenka.aggregation.model.AggregationEnrichment;
import dev.abramenka.aggregation.model.AggregationPartResult;
import dev.abramenka.aggregation.model.PartSkipReason;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

@Component
@Slf4j
class BeneficialOwnersEnrichment implements AggregationEnrichment {

    static final String NAME = "beneficialOwners";
    private static final String TREE_METRIC = "aggregation.beneficial_owners.tree";

    private final OwnershipResolver resolver;
    private final RootEntityTargets rootEntityTargets;
    private final BeneficialOwnersDetailsPayload detailsPayload;
    private final MeterRegistry meterRegistry;

    BeneficialOwnersEnrichment(
            OwnershipResolver resolver,
            RootEntityTargets rootEntityTargets,
            BeneficialOwnersDetailsPayload detailsPayload,
            MeterRegistry meterRegistry) {
        this.resolver = resolver;
        this.rootEntityTargets = rootEntityTargets;
        this.detailsPayload = detailsPayload;
        this.meterRegistry = meterRegistry;
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
    public Mono<AggregationPartResult> execute(AggregationContext context) {
        if (rootEntityTargets.collect(context.accountGroupResponse()).isEmpty()) {
            return Mono.just(AggregationPartResult.skipped(NAME, PartSkipReason.NO_KEYS_IN_MAIN));
        }
        return AggregationEnrichment.super.execute(context);
    }

    @Override
    public Mono<JsonNode> fetch(AggregationContext context) {
        List<RootEntityTarget> rootEntities = rootEntityTargets.collect(context.accountGroupResponse());
        int concurrency = Math.max(1, rootEntities.size());
        return Flux.fromIterable(rootEntities)
                .flatMap(entity -> resolveOne(entity, context), concurrency)
                .collectList()
                .map(detailsPayload::response);
    }

    @Override
    public void merge(ObjectNode root, JsonNode enrichmentResponse) {
        detailsPayload.merge(root, enrichmentResponse);
    }

    private Mono<ResolvedEntity> resolveOne(RootEntityTarget entity, AggregationContext context) {
        return resolver.resolveTree(entity.node(), context)
                .doOnSuccess(array -> recordTree("success"))
                .doOnError(ex -> {
                    recordTree("failure");
                    log.warn("Beneficial-owners resolution for a root entity failed: {}", ex.getMessage());
                })
                .onErrorMap(BeneficialOwnersResolutionException.class, this::mapResolutionException)
                .map(array -> new ResolvedEntity(entity.dataIndex(), entity.ownerIndex(), array));
    }

    private Throwable mapResolutionException(BeneficialOwnersResolutionException ex) {
        Throwable cause = ex.getCause();
        if (cause instanceof FacadeException facadeException) {
            return facadeException;
        }
        return EnrichmentDependencyException.contractViolation(NAME, ex);
    }

    private void recordTree(String outcome) {
        meterRegistry.counter(TREE_METRIC, "outcome", outcome).increment();
    }
}
