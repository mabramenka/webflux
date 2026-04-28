package dev.abramenka.aggregation.enrichment.beneficialowners;

import dev.abramenka.aggregation.client.Owners;
import dev.abramenka.aggregation.model.PartCriticality;
import dev.abramenka.aggregation.workflow.AggregationWorkflow;
import dev.abramenka.aggregation.workflow.WorkflowAggregationPart;
import dev.abramenka.aggregation.workflow.WorkflowExecutor;
import dev.abramenka.aggregation.workflow.ownership.WriteOwnership;
import dev.abramenka.aggregation.workflow.recursive.TraversalReducerStep;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
class BeneficialOwnersEnrichment extends WorkflowAggregationPart {

    static final String NAME = "beneficialOwners";
    static final String TRAVERSAL_RESULT_VAR = "beneficialOwnersTraversal";

    BeneficialOwnersEnrichment(
            Owners ownersClient,
            RootEntityTargets rootEntityTargets,
            ObjectMapper objectMapper,
            WorkflowExecutor executor,
            MeterRegistry meterRegistry) {
        super(
                new AggregationWorkflow(
                        NAME,
                        Set.of("owners"),
                        PartCriticality.REQUIRED,
                        List.of(
                                new RequireRootEntityOwnersStep(
                                        "requireRootEntityOwners",
                                        rootEntityTargets,
                                        "__beneficialOwners.targetsPresent"),
                                new BeneficialOwnersRecursiveFetchStep(
                                        "recursiveFetch",
                                        TRAVERSAL_RESULT_VAR,
                                        ownersClient,
                                        objectMapper,
                                        rootEntityTargets,
                                        meterRegistry),
                                new TraversalReducerStep(
                                        "reduceTraversal",
                                        TRAVERSAL_RESULT_VAR,
                                        new BeneficialOwnersTraversalReducer())),
                        WriteOwnership.of("beneficialOwnersDetails")),
                executor);
    }
}
