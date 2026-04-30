package dev.abramenka.aggregation.enrichment.beneficialowners;

import dev.abramenka.aggregation.client.Owners;
import dev.abramenka.aggregation.error.EnrichmentDependencyException;
import dev.abramenka.aggregation.model.PartCriticality;
import dev.abramenka.aggregation.workflow.AggregationWorkflow;
import dev.abramenka.aggregation.workflow.StepResult;
import dev.abramenka.aggregation.workflow.WorkflowAggregationPart;
import dev.abramenka.aggregation.workflow.WorkflowContext;
import dev.abramenka.aggregation.workflow.WorkflowExecutor;
import dev.abramenka.aggregation.workflow.WorkflowStep;
import dev.abramenka.aggregation.workflow.ownership.WriteOwnership;
import dev.abramenka.aggregation.workflow.recursive.RecursiveTraversalEngine;
import dev.abramenka.aggregation.workflow.recursive.TraversalReducerStep;
import dev.abramenka.aggregation.workflow.recursive.TraversalResult;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;

@Component
class BeneficialOwnersEnrichment extends WorkflowAggregationPart {

    static final String NAME = "beneficialOwners";
    static final String TRAVERSAL_RESULT_VAR = "beneficialOwnersTraversal";

    BeneficialOwnersEnrichment(
            Owners ownersClient,
            RootEntityTargets rootEntityTargets,
            ObjectMapper objectMapper,
            WorkflowExecutor executor) {
        super(workflow(ownersClient, rootEntityTargets, objectMapper), executor);
    }

    private static AggregationWorkflow workflow(
            Owners ownersClient, RootEntityTargets rootEntityTargets, ObjectMapper objectMapper) {
        BeneficialOwnersRecursiveFetcher recursiveFetcher =
                new BeneficialOwnersRecursiveFetcher(ownersClient, objectMapper, rootEntityTargets);
        return new AggregationWorkflow(
                NAME,
                Set.of("owners"),
                PartCriticality.REQUIRED,
                List.of(
                        new RequireRootEntityOwnersStep(
                                "requireRootEntityOwners", rootEntityTargets, "__beneficialOwners.targetsPresent"),
                        new RecursiveFetchAdapterStep("recursiveFetch", TRAVERSAL_RESULT_VAR, recursiveFetcher),
                        new TraversalReducerStep(
                                "reduceTraversal", TRAVERSAL_RESULT_VAR, new BeneficialOwnersTraversalReducer())),
                WriteOwnership.of("beneficialOwnersDetails"));
    }

    private static Throwable mapFetchError(Throwable error) {
        return switch (error) {
            case BeneficialOwnersRecursiveFetchException ignored ->
                EnrichmentDependencyException.contractViolation(NAME, error);
            case RecursiveTraversalEngine.TraversalException traversalException ->
                switch (traversalException.reason()) {
                    case DEPTH_EXCEEDED, CONTRACT_VIOLATION ->
                        EnrichmentDependencyException.contractViolation(NAME, traversalException);
                };
            default -> error;
        };
    }

    private record RecursiveFetchAdapterStep(
            String name, String storeAs, BeneficialOwnersRecursiveFetcher recursiveFetcher) implements WorkflowStep {

        private RecursiveFetchAdapterStep {
            if (name.isBlank()) {
                throw new IllegalArgumentException("RecursiveFetchAdapterStep name must not be blank");
            }
            if (storeAs.isBlank()) {
                throw new IllegalArgumentException("RecursiveFetchAdapterStep storeAs must not be blank");
            }
            Objects.requireNonNull(recursiveFetcher, "recursiveFetcher");
        }

        @Override
        public Mono<StepResult> execute(WorkflowContext context) {
            return recursiveFetcher
                    .fetch(context.rootSnapshot(), context.aggregationContext())
                    .map(result -> storedResult(storeAs, result))
                    .onErrorMap(BeneficialOwnersEnrichment::mapFetchError);
        }

        private static StepResult storedResult(String storeAs, TraversalResult result) {
            return StepResult.stored(storeAs, result.toJsonNode(JsonNodeFactory.instance));
        }
    }
}
