package dev.abramenka.aggregation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.abramenka.aggregation.enrichment.AggregationEnrichment;
import dev.abramenka.aggregation.error.UnsupportedAggregationPartException;
import dev.abramenka.aggregation.model.AggregationContext;
import dev.abramenka.aggregation.model.AggregationPartSelection;
import dev.abramenka.aggregation.model.ClientRequestContext;
import dev.abramenka.aggregation.model.ForwardedHeaders;
import dev.abramenka.aggregation.postprocessor.AggregationPostProcessor;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class AggregationPartPlannerTest {

    @Test
    void plan_expandsDependenciesAcrossAggregationParts() {
        AggregationPartPlanner planner = new AggregationPartPlanner(
                List.of(enrichment("account"), enrichment("auditTrail", "account")),
                List.of(postProcessor("beneficialOwners", "auditTrail")));

        AggregationPartPlan plan = planner.plan(List.of("beneficialOwners"));

        assertThat(plan.requestedSelection().names()).containsExactly("beneficialOwners");
        assertThat(plan.effectiveSelection().names())
                .containsExactlyInAnyOrder("beneficialOwners", "auditTrail", "account");
        assertThat(plan.selectedEnrichments())
                .extracting(AggregationEnrichment::name)
                .containsExactly("account", "auditTrail");
        assertThat(plan.selectedPostProcessors())
                .extracting(AggregationPostProcessor::name)
                .containsExactly("beneficialOwners");
    }

    @Test
    void plan_ordersSelectedEnrichmentsByDependencies() {
        AggregationPartPlanner planner = new AggregationPartPlanner(
                List.of(enrichment("auditTrail", "account"), enrichment("account")), List.of());

        AggregationPartPlan plan = planner.plan(List.of("auditTrail"));

        assertThat(plan.selectedEnrichments())
                .extracting(AggregationEnrichment::name)
                .containsExactly("account", "auditTrail");
    }

    @Test
    void plan_ordersSelectedPostProcessorsByDependencies() {
        AggregationPartPlanner planner = new AggregationPartPlanner(
                List.of(), List.of(postProcessor("summary", "beneficialOwners"), postProcessor("beneficialOwners")));

        AggregationPartPlan plan = planner.plan(List.of("summary"));

        assertThat(plan.selectedPostProcessors())
                .extracting(AggregationPostProcessor::name)
                .containsExactly("beneficialOwners", "summary");
    }

    @Test
    void plan_filtersUnsupportedPostProcessors() {
        AggregationPartPlanner planner =
                new AggregationPartPlanner(List.of(), List.of(unsupportedPostProcessor("beneficialOwners")));

        AggregationPartPlan plan = planner.plan(List.of("beneficialOwners"));

        assertThat(plan.selectedPostProcessors())
                .extracting(AggregationPostProcessor::name)
                .containsExactly("beneficialOwners");
        assertThat(plan.executionPlan(context()).postProcessorPhase()).isEmpty();
    }

    @Test
    void plan_rejectsUnknownRequestedPart() {
        AggregationPartPlanner planner = new AggregationPartPlanner(List.of(enrichment("account")), List.of());

        assertThatThrownBy(() -> planner.plan(List.of("missing")))
                .isInstanceOf(UnsupportedAggregationPartException.class)
                .hasMessageContaining("missing");
    }

    @Test
    void constructor_rejectsDuplicatePartNames() {
        assertThatThrownBy(() ->
                        new AggregationPartPlanner(List.of(enrichment("account")), List.of(postProcessor("account"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate aggregation component name: account");
    }

    @Test
    void constructor_rejectsUnknownDependencies() {
        assertThatThrownBy(() -> new AggregationPartPlanner(List.of(enrichment("account", "missing")), List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unknown aggregation component dependency for account: missing");
    }

    @Test
    void constructor_rejectsCyclicDependencies() {
        assertThatThrownBy(() -> new AggregationPartPlanner(
                        List.of(enrichment("account", "owners"), enrichment("owners", "account")), List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cyclic aggregation component dependency");
    }

    @Test
    void constructor_rejectsEnrichmentDependingOnPostProcessor() {
        assertThatThrownBy(() -> new AggregationPartPlanner(
                        List.of(enrichment("auditTrail", "beneficialOwners")),
                        List.of(postProcessor("beneficialOwners"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(
                        "Aggregation enrichment auditTrail depends on post-processor(s): beneficialOwners");
    }

    private static AggregationEnrichment enrichment(String name, String... dependencies) {
        return new AggregationEnrichment() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Set<String> dependencies() {
                return Set.of(dependencies);
            }

            @Override
            public boolean supports(AggregationContext context) {
                return true;
            }

            @Override
            public Mono<JsonNode> fetch(AggregationContext context) {
                return Mono.empty();
            }

            @Override
            public void merge(ObjectNode root, JsonNode enrichmentResponse) {}
        };
    }

    private static AggregationPostProcessor postProcessor(String name, String... dependencies) {
        return new AggregationPostProcessor() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Set<String> dependencies() {
                return Set.of(dependencies);
            }

            @Override
            public Mono<Void> apply(ObjectNode root, AggregationContext context) {
                return Mono.empty();
            }
        };
    }

    private static AggregationPostProcessor unsupportedPostProcessor(String name) {
        return new AggregationPostProcessor() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public boolean supports(AggregationContext context) {
                return false;
            }

            @Override
            public Mono<Void> apply(ObjectNode root, AggregationContext context) {
                return Mono.empty();
            }
        };
    }

    private static AggregationContext context() {
        return new AggregationContext(
                JsonMapper.builder().build().createObjectNode(),
                new ClientRequestContext(ForwardedHeaders.builder().build(), null),
                AggregationPartSelection.from(null));
    }
}
