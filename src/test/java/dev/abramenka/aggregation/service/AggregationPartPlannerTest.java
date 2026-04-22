package dev.abramenka.aggregation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.abramenka.aggregation.enrichment.AggregationEnrichment;
import dev.abramenka.aggregation.error.UnsupportedAggregationPartException;
import dev.abramenka.aggregation.model.AggregationContext;
import dev.abramenka.aggregation.postprocessor.AggregationPostProcessor;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
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
}
