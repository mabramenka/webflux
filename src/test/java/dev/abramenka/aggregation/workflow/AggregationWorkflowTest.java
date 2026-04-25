package dev.abramenka.aggregation.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.abramenka.aggregation.model.PartCriticality;
import dev.abramenka.aggregation.patch.JsonPatchBuilder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class AggregationWorkflowTest {

    @Test
    void copiesDependenciesAndStepsDefensively() {
        Set<String> deps = new HashSet<>(Set.of("dep"));
        List<WorkflowStep> steps = new ArrayList<>(List.of(stubStep("s1")));

        AggregationWorkflow workflow = new AggregationWorkflow("w", deps, PartCriticality.REQUIRED, steps);

        deps.add("mutated");
        steps.add(stubStep("s2"));

        assertThat(workflow.dependencies()).containsExactly("dep");
        assertThat(workflow.steps()).hasSize(1);
    }

    @Test
    void rejectsBlankName() {
        assertThatThrownBy(
                        () -> new AggregationWorkflow(" ", Set.of(), PartCriticality.REQUIRED, List.of(stubStep("s"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank");
    }

    @Test
    void rejectsEmptySteps() {
        assertThatThrownBy(() -> new AggregationWorkflow("w", Set.of(), PartCriticality.REQUIRED, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one step");
    }

    private static WorkflowStep stubStep(String name) {
        return new WorkflowStep() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Mono<StepResult> execute(WorkflowContext context) {
                return Mono.just(StepResult.applied(JsonPatchBuilder.create().build()));
            }
        };
    }
}
