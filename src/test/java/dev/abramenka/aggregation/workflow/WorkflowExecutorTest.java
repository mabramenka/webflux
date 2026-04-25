package dev.abramenka.aggregation.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import dev.abramenka.aggregation.model.AggregationContext;
import dev.abramenka.aggregation.model.AggregationPartResult;
import dev.abramenka.aggregation.model.ClientRequestContext;
import dev.abramenka.aggregation.model.ForwardedHeaders;
import dev.abramenka.aggregation.model.PartCriticality;
import dev.abramenka.aggregation.model.PartOutcomeStatus;
import dev.abramenka.aggregation.model.PartSkipReason;
import dev.abramenka.aggregation.model.Projections;
import dev.abramenka.aggregation.patch.JsonPatchBuilder;
import dev.abramenka.aggregation.patch.JsonPatchDocument;
import java.util.List;
import java.util.Set;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class WorkflowExecutorTest {

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final WorkflowExecutor executor = new WorkflowExecutor();

    @Test
    void combinesAppliedPatchesInOrder() {
        WorkflowStep first = step(
                "s1",
                ctx -> StepResult.applied(JsonPatchBuilder.create()
                        .add("/a", mapper.getNodeFactory().stringNode("x"))
                        .build()));
        WorkflowStep second = step(
                "s2",
                ctx -> StepResult.applied(JsonPatchBuilder.create()
                        .replace("/a", mapper.getNodeFactory().stringNode("y"))
                        .build()));
        AggregationWorkflow workflow =
                new AggregationWorkflow("w", Set.of(), PartCriticality.REQUIRED, List.of(first, second));

        StepVerifier.create(executor.execute(workflow, contextWithEmptyRoot()))
                .assertNext(result -> {
                    assertThat(result.patch())
                            .isNotNull()
                            .extracting(JsonPatchDocument::operations)
                            .asInstanceOf(InstanceOfAssertFactories.LIST)
                            .hasSize(2);
                    assertThat(result.toPartResult("w")).isInstanceOf(AggregationPartResult.JsonPatch.class);
                })
                .verifyComplete();
    }

    @Test
    void firstSkippedStepShortCircuits() {
        WorkflowStep skipping = step("s1", ctx -> StepResult.skipped(PartSkipReason.NO_KEYS_IN_MAIN));
        WorkflowStep neverRuns = step("s2", ctx -> {
            throw new AssertionError("should not run after skip");
        });
        AggregationWorkflow workflow =
                new AggregationWorkflow("w", Set.of(), PartCriticality.REQUIRED, List.of(skipping, neverRuns));

        StepVerifier.create(executor.execute(workflow, contextWithEmptyRoot()))
                .assertNext(result -> {
                    assertThat(result.softStatus()).isEqualTo(WorkflowResult.SoftStatus.SKIPPED);
                    AggregationPartResult.NoOp noOp = (AggregationPartResult.NoOp) result.toPartResult("w");
                    assertThat(noOp.status()).isEqualTo(PartOutcomeStatus.SKIPPED);
                    assertThat(noOp.reason()).isEqualTo(PartSkipReason.NO_KEYS_IN_MAIN);
                })
                .verifyComplete();
    }

    @Test
    void emptyOutcomeMapsToEmptyPartResult() {
        WorkflowStep emptyStep = step("s1", ctx -> StepResult.empty(PartSkipReason.DOWNSTREAM_EMPTY));
        AggregationWorkflow workflow =
                new AggregationWorkflow("w", Set.of(), PartCriticality.REQUIRED, List.of(emptyStep));

        StepVerifier.create(executor.execute(workflow, contextWithEmptyRoot()))
                .assertNext(result -> {
                    assertThat(result.softStatus()).isEqualTo(WorkflowResult.SoftStatus.EMPTY);
                    AggregationPartResult.NoOp noOp = (AggregationPartResult.NoOp) result.toPartResult("w");
                    assertThat(noOp.status()).isEqualTo(PartOutcomeStatus.EMPTY);
                })
                .verifyComplete();
    }

    @Test
    void storedValuesAreVisibleToLaterSteps() {
        WorkflowStep storing = step(
                "store", ctx -> StepResult.stored("k", mapper.getNodeFactory().numberNode(42)));
        WorkflowStep reading = step("read", ctx -> {
            assertThat(ctx.variables().get("k")).isPresent();
            return StepResult.applied(JsonPatchBuilder.create().build());
        });
        AggregationWorkflow workflow =
                new AggregationWorkflow("w", Set.of(), PartCriticality.REQUIRED, List.of(storing, reading));

        StepVerifier.create(executor.execute(workflow, contextWithEmptyRoot()))
                .assertNext(result -> assertThat(result.patch()).isNotNull())
                .verifyComplete();
    }

    private AggregationContext contextWithEmptyRoot() {
        ObjectNode root = mapper.createObjectNode();
        ClientRequestContext clientCtx =
                new ClientRequestContext(ForwardedHeaders.builder().build(), null, Projections.empty());
        return new AggregationContext(root, clientCtx);
    }

    private WorkflowStep step(String name, java.util.function.Function<WorkflowContext, StepResult> body) {
        return new WorkflowStep() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Mono<StepResult> execute(WorkflowContext context) {
                return Mono.fromSupplier(() -> body.apply(context));
            }
        };
    }
}
