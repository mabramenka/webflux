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
import dev.abramenka.aggregation.workflow.binding.BindingName;
import dev.abramenka.aggregation.workflow.binding.DownstreamBinding;
import dev.abramenka.aggregation.workflow.binding.DownstreamCall;
import dev.abramenka.aggregation.workflow.binding.KeyExtractionRule;
import dev.abramenka.aggregation.workflow.binding.KeySource;
import dev.abramenka.aggregation.workflow.binding.ResponseIndexingRule;
import dev.abramenka.aggregation.workflow.binding.WriteRule;
import dev.abramenka.aggregation.workflow.step.KeyedBindingStep;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.JsonNode;
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

    // -------------------------------------------------------------------------
    // Integration: WorkflowExecutor + KeyedBindingStep
    // -------------------------------------------------------------------------

    @Test
    void workflowWithKeyedBindingStep_producesJsonPatch() {
        ObjectNode root = parseObject("""
                {"data": [{"id": "a1"}]}
                """);
        JsonNode response = parseObject("""
                {"items": [{"id": "a1", "details": "d1"}]}
                """);

        KeyedBindingStep keyedStep = new KeyedBindingStep("enrich", keyedBinding((keys, ctx) -> Mono.just(response)));
        AggregationWorkflow workflow =
                new AggregationWorkflow("test", Set.of(), PartCriticality.REQUIRED, List.of(keyedStep));

        StepVerifier.create(executor.execute(workflow, contextWith(root)))
                .assertNext(result -> {
                    JsonPatchDocument patch = Objects.requireNonNull(result.patch(), "patch");
                    assertThat(patch.operations()).hasSize(1);
                    assertThat(result.toPartResult("test")).isInstanceOf(AggregationPartResult.JsonPatch.class);
                })
                .verifyComplete();
    }

    @Test
    void softOutcomeFromKeyedBindingStep_mapsToNoOp() {
        // Root has no items → KeyedBindingStep will produce SKIPPED / NO_KEYS_IN_MAIN
        ObjectNode root = parseObject("""
                {"data": []}
                """);

        KeyedBindingStep keyedStep = new KeyedBindingStep("enrich", keyedBinding((keys, ctx) -> Mono.empty()));
        AggregationWorkflow workflow =
                new AggregationWorkflow("test", Set.of(), PartCriticality.REQUIRED, List.of(keyedStep));

        StepVerifier.create(executor.execute(workflow, contextWith(root)))
                .assertNext(result -> {
                    assertThat(result.softStatus()).isEqualTo(WorkflowResult.SoftStatus.SKIPPED);
                    AggregationPartResult partResult = result.toPartResult("test");
                    assertThat(partResult).isInstanceOf(AggregationPartResult.NoOp.class);
                    assertThat(((AggregationPartResult.NoOp) partResult).status())
                            .isEqualTo(PartOutcomeStatus.SKIPPED);
                    assertThat(((AggregationPartResult.NoOp) partResult).reason())
                            .isEqualTo(PartSkipReason.NO_KEYS_IN_MAIN);
                })
                .verifyComplete();
    }

    private AggregationContext contextWithEmptyRoot() {
        ObjectNode root = mapper.createObjectNode();
        return contextWith(root);
    }

    private AggregationContext contextWith(ObjectNode root) {
        ClientRequestContext clientCtx =
                new ClientRequestContext(ForwardedHeaders.builder().build(), null, Projections.empty());
        return new AggregationContext(root, clientCtx);
    }

    private DownstreamBinding keyedBinding(DownstreamCall call) {
        KeyExtractionRule keyRule = new KeyExtractionRule(KeySource.ROOT_SNAPSHOT, null, "$.data[*]", List.of("id"));
        ResponseIndexingRule indexRule = new ResponseIndexingRule("$.items[*]", List.of("id"));
        WriteRule writeRule = new WriteRule(
                "$.data[*]", new WriteRule.MatchBy("id", "id"), new WriteRule.WriteAction.ReplaceField("enriched"));
        return new DownstreamBinding(new BindingName("b"), keyRule, call, indexRule, null, writeRule);
    }

    private ObjectNode parseObject(String json) {
        try {
            return (ObjectNode) mapper.readTree(json);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
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
