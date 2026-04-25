package dev.abramenka.aggregation.workflow;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.abramenka.aggregation.model.PartCriticality;
import dev.abramenka.aggregation.patch.JsonPatchBuilder;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class WorkflowDefinitionValidatorTest {

    @Test
    void rejectsDuplicateStepNames() {
        AggregationWorkflow workflow =
                new AggregationWorkflow("w", Set.of(), PartCriticality.REQUIRED, List.of(stubStep("a"), stubStep("a")));

        assertThatThrownBy(() -> WorkflowDefinitionValidator.validate(workflow))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate step name");
    }

    @Test
    void rejectsBlankStepName() {
        AggregationWorkflow workflow =
                new AggregationWorkflow("w", Set.of(), PartCriticality.REQUIRED, List.of(stubStep(" ")));

        assertThatThrownBy(() -> WorkflowDefinitionValidator.validate(workflow))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("blank name");
    }

    @Test
    void acceptsUniqueStepNames() {
        AggregationWorkflow workflow =
                new AggregationWorkflow("w", Set.of(), PartCriticality.REQUIRED, List.of(stubStep("a"), stubStep("b")));

        assertThatCode(() -> WorkflowDefinitionValidator.validate(workflow)).doesNotThrowAnyException();
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
