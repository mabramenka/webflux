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
    void plan_includeEmpty_selectsOnlyBasePart() {
        AggregationPartPlanner planner =
                new AggregationPartPlanner(List.of(basePart(), enrichment("account", "accountGroup")));

        AggregationPartPlan plan = planner.plan(List.of());

        assertThat(plan.selectedLevels())
                .extracting(AggregationPartPlannerTest::partNames)
                .containsExactly(List.of("accountGroup"));
    }

    @Test
    void plan_includeNull_selectsBaseAndAllPublicParts() {
        AggregationPartPlanner planner = new AggregationPartPlanner(List.of(
                basePart(),
                enrichment("beneficialOwners", "owners"),
                enrichment("account", "accountGroup"),
                enrichment("owners", "accountGroup")));

        AggregationPartPlan plan = planner.plan(null);

        List<List<String>> levels = plan.selectedLevels().stream()
                .map(AggregationPartPlannerTest::partNames)
                .toList();
        assertThat(levels).hasSize(3);
        assertThat(levels.get(0)).containsExactly("accountGroup");
        assertThat(levels.get(1)).containsExactlyInAnyOrder("account", "owners");
        assertThat(levels.get(2)).containsExactly("beneficialOwners");
    }

    @Test
    void plan_includeAccount_selectsBaseAndAccount() {
        AggregationPartPlanner planner = new AggregationPartPlanner(
                List.of(basePart(), enrichment("account", "accountGroup"), enrichment("owners", "accountGroup")));

        AggregationPartPlan plan = planner.plan(List.of("account"));

        assertThat(plan.selectedLevels())
                .extracting(AggregationPartPlannerTest::partNames)
                .containsExactly(List.of("accountGroup"), List.of("account"));
    }

    @Test
    void plan_includeBeneficialOwners_expandsOwnersDependency() {
        AggregationPartPlanner planner = new AggregationPartPlanner(List.of(
                basePart(),
                enrichment("beneficialOwners", "owners"),
                enrichment("owners", "accountGroup"),
                enrichment("account", "accountGroup")));

        AggregationPartPlan plan = planner.plan(List.of("beneficialOwners"));

        assertThat(plan.selectedLevels())
                .extracting(AggregationPartPlannerTest::partNames)
                .containsExactly(List.of("accountGroup"), List.of("owners"), List.of("beneficialOwners"));
    }

    @Test
    void plan_rejectsNonPublicPartInInclude() {
        AggregationPartPlanner planner =
                new AggregationPartPlanner(List.of(basePart(), enrichment("account", "accountGroup")));
        List<String> include = List.of("accountGroup");

        assertThatThrownBy(() -> planner.plan(include))
                .isInstanceOf(UnsupportedAggregationPartException.class)
                .hasMessage("One or more request fields failed validation.");
    }

    @Test
    void plan_rejectsUnknownRequestedPart() {
        AggregationPartPlanner planner =
                new AggregationPartPlanner(List.of(basePart(), enrichment("account", "accountGroup")));
        List<String> include = List.of("missing");

        assertThatThrownBy(() -> planner.plan(include))
                .isInstanceOf(UnsupportedAggregationPartException.class)
                .hasMessage("One or more request fields failed validation.");
    }

    @Test
    void constructor_rejectsMissingBasePart() {
        List<AggregationPart> parts = List.of(enrichment("account"));

        assertThatThrownBy(() -> new AggregationPartPlanner(parts))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Exactly one base aggregation part is required");
    }

    @Test
    void constructor_rejectsMultipleBaseParts() {
        List<AggregationPart> parts = List.of(basePart(), secondBasePart());

        assertThatThrownBy(() -> new AggregationPartPlanner(parts))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Exactly one base aggregation part is required");
    }

    @Test
    void constructor_rejectsBaseDependencies() {
        List<AggregationPart> parts = List.of(basePart("owners"), enrichment("owners", "accountGroup"));

        assertThatThrownBy(() -> new AggregationPartPlanner(parts))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must not declare dependencies");
    }

    @Test
    void constructor_rejectsDuplicatePartNames() {
        List<AggregationPart> parts = List.of(basePart(), enrichment("account"), enrichment("account"));

        assertThatThrownBy(() -> new AggregationPartPlanner(parts))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate aggregation component name: account");
    }

    @Test
    void constructor_rejectsUnknownDependencies() {
        List<AggregationPart> parts = List.of(basePart(), enrichment("account", "missing"));

        assertThatThrownBy(() -> new AggregationPartPlanner(parts))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unknown aggregation component dependency for account: missing");
    }

    @Test
    void constructor_rejectsCyclicDependencies() {
        List<AggregationPart> parts =
                List.of(basePart(), enrichment("account", "owners"), enrichment("owners", "account"));

        assertThatThrownBy(() -> new AggregationPartPlanner(parts))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cyclic aggregation component dependency");
    }

    @Test
    void plan_defensivelyCopiesSelectedLevels() {
        AggregationPart part = basePart();
        List<AggregationPart> level = new ArrayList<>(List.of(part));
        List<List<AggregationPart>> levels = new ArrayList<>(List.of(level));

        AggregationPartPlan plan = new AggregationPartPlan(
                AggregationPartSelection.from(null), AggregationPartSelection.from(null), levels);
        level.clear();
        levels.clear();

        assertThat(plan.selectedLevels())
                .extracting(AggregationPartPlannerTest::partNames)
                .containsExactly(List.of("accountGroup"));
        List<List<AggregationPart>> selectedLevels = plan.selectedLevels();
        List<AggregationPart> appendedLevel = List.of(part);
        List<AggregationPart> firstLevel = selectedLevels.getFirst();

        assertThatThrownBy(() -> selectedLevels.add(appendedLevel)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> firstLevel.add(part)).isInstanceOf(UnsupportedOperationException.class);
    }

    private static AggregationPart basePart(String... dependencies) {
        return new AggregationPart() {
            @Override
            public String name() {
                return "accountGroup";
            }

            @Override
            public boolean base() {
                return true;
            }

            @Override
            public boolean publicSelectable() {
                return false;
            }

            @Override
            public Set<String> dependencies() {
                return Set.of(dependencies);
            }

            @Override
            public Mono<AggregationPartResult> execute(AggregationContext context) {
                return Mono.just(AggregationPartResult.replacement(name(), context.accountGroupResponse()));
            }
        };
    }

    private static AggregationPart secondBasePart() {
        return new AggregationPart() {
            @Override
            public String name() {
                return "anotherBase";
            }

            @Override
            public boolean base() {
                return true;
            }

            @Override
            public boolean publicSelectable() {
                return false;
            }

            @Override
            public Mono<AggregationPartResult> execute(AggregationContext context) {
                return Mono.just(AggregationPartResult.replacement(name(), context.accountGroupResponse()));
            }
        };
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

    private static List<String> partNames(List<AggregationPart> parts) {
        return parts.stream().map(AggregationPart::name).toList();
    }
}
