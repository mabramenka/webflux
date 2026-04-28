package dev.abramenka.aggregation.part;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.abramenka.aggregation.error.UnsupportedAggregationPartException;
import dev.abramenka.aggregation.model.AggregationContext;
import dev.abramenka.aggregation.model.AggregationPart;
import dev.abramenka.aggregation.model.AggregationPartPlan;
import dev.abramenka.aggregation.model.AggregationPartResult;
import dev.abramenka.aggregation.model.AggregationPartSelection;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class AggregationPartPlannerTest {

    @Test
    void plan_expandsDependenciesAcrossAggregationParts() {
        AggregationPartPlanner planner = new AggregationPartPlanner(List.of(
                enrichment("account"),
                enrichment("auditTrail", "account"),
                enrichment("beneficialOwners", "auditTrail")));

        AggregationPartPlan plan = planner.plan(List.of("beneficialOwners"));

        assertThat(plan.requestedSelection().names()).containsExactly("beneficialOwners");
        assertThat(plan.effectiveSelection().names())
                .containsExactlyInAnyOrder("beneficialOwners", "auditTrail", "account");
        assertThat(plan.selectedLevels())
                .extracting(AggregationPartPlannerTest::partNames)
                .containsExactly(List.of("account"), List.of("auditTrail"), List.of("beneficialOwners"));
    }

    @Test
    void plan_ordersSelectedEnrichmentsByDependencies() {
        AggregationPartPlanner planner =
                new AggregationPartPlanner(List.of(enrichment("auditTrail", "account"), enrichment("account")));

        AggregationPartPlan plan = planner.plan(List.of("auditTrail"));

        assertThat(plan.selectedLevels())
                .extracting(AggregationPartPlannerTest::partNames)
                .containsExactly(List.of("account"), List.of("auditTrail"));
    }

    @Test
    void plan_ordersSelectedEnrichmentsByTransitiveDependencies() {
        AggregationPartPlanner planner = new AggregationPartPlanner(
                List.of(enrichment("summary", "beneficialOwners"), enrichment("beneficialOwners")));

        AggregationPartPlan plan = planner.plan(List.of("summary"));

        assertThat(plan.selectedLevels())
                .extracting(AggregationPartPlannerTest::partNames)
                .containsExactly(List.of("beneficialOwners"), List.of("summary"));
    }

    @Test
    void plan_keepsSelectedPartsForRuntimeSupportCheck() {
        AggregationPartPlanner planner = new AggregationPartPlanner(List.of(unsupportedEnrichment("beneficialOwners")));

        AggregationPartPlan plan = planner.plan(List.of("beneficialOwners"));

        assertThat(plan.selectedLevels())
                .extracting(AggregationPartPlannerTest::partNames)
                .containsExactly(List.of("beneficialOwners"));
    }

    @Test
    void plan_rejectsUnknownRequestedPart() {
        AggregationPartPlanner planner = new AggregationPartPlanner(List.of(enrichment("account")));

        assertThatThrownBy(() -> planner.plan(List.of("missing")))
                .isInstanceOf(UnsupportedAggregationPartException.class)
                .hasMessage("One or more request fields failed validation.");
    }

    @Test
    void constructor_rejectsDuplicatePartNames() {
        assertThatThrownBy(() -> new AggregationPartPlanner(List.of(enrichment("account"), enrichment("account"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate aggregation component name: account");
    }

    @Test
    void constructor_rejectsUnknownDependencies() {
        assertThatThrownBy(() -> new AggregationPartPlanner(List.of(enrichment("account", "missing"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unknown aggregation component dependency for account: missing");
    }

    @Test
    void constructor_rejectsCyclicDependencies() {
        assertThatThrownBy(() -> new AggregationPartPlanner(
                        List.of(enrichment("account", "owners"), enrichment("owners", "account"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cyclic aggregation component dependency");
    }

    @Test
    void constructor_allowsDependenciesAcrossSelectedParts() {
        AggregationPartPlanner planner = new AggregationPartPlanner(
                List.of(enrichment("auditTrail", "beneficialOwners"), enrichment("beneficialOwners")));

        AggregationPartPlan plan = planner.plan(List.of("auditTrail"));

        assertThat(plan.selectedLevels())
                .extracting(AggregationPartPlannerTest::partNames)
                .containsExactly(List.of("beneficialOwners"), List.of("auditTrail"));
    }

    @Test
    void plan_defensivelyCopiesSelectedLevels() {
        AggregationPart part = enrichment("account");
        List<AggregationPart> level = new ArrayList<>(List.of(part));
        List<List<AggregationPart>> levels = new ArrayList<>(List.of(level));

        AggregationPartPlan plan = new AggregationPartPlan(
                AggregationPartSelection.from(null), AggregationPartSelection.from(null), levels);
        level.clear();
        levels.clear();

        assertThat(plan.selectedLevels())
                .extracting(AggregationPartPlannerTest::partNames)
                .containsExactly(List.of("account"));
        assertThatThrownBy(() -> plan.selectedLevels().add(List.of(part)))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> plan.selectedLevels().getFirst().add(part))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static AggregationPart enrichment(String name, String... dependencies) {
        return new AggregationPart() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Set<String> dependencies() {
                return Set.of(dependencies);
            }

            @Override
            public Mono<AggregationPartResult> execute(AggregationContext context) {
                return Mono.just(AggregationPartResult.patch(
                        name(),
                        context.accountGroupResponse(),
                        context.accountGroupResponse().deepCopy()));
            }
        };
    }

    private static AggregationPart unsupportedEnrichment(String name) {
        return new AggregationPart() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public boolean supports(AggregationContext context) {
                return false;
            }

            @Override
            public Mono<AggregationPartResult> execute(AggregationContext context) {
                return Mono.just(AggregationPartResult.patch(
                        name(),
                        context.accountGroupResponse(),
                        context.accountGroupResponse().deepCopy()));
            }
        };
    }

    private static List<String> partNames(List<AggregationPart> parts) {
        return parts.stream().map(AggregationPart::name).toList();
    }
}
