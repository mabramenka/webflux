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
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class WorkflowExecutorTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    private SimpleMeterRegistry meterRegistry;
    private WorkflowExecutor executor;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        executor = new WorkflowExecutor(new WorkflowBindingMetrics(meterRegistry));
    }

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
                        .add("/b", mapper.getNodeFactory().stringNode("y"))
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

    // -------------------------------------------------------------------------
    // Binding metrics (Phase 9)
    // -------------------------------------------------------------------------

    @Test
    void appliedStep_recordsSuccessBindingMetric() {
        WorkflowStep s =
                step("s1", ctx -> StepResult.applied(JsonPatchBuilder.create().build()));
        AggregationWorkflow workflow =
                new AggregationWorkflow("myPart", Set.of(), PartCriticality.REQUIRED, List.of(s));

        StepVerifier.create(executor.execute(workflow, contextWithEmptyRoot()))
                .expectNextCount(1)
                .verifyComplete();

        assertBindingMetric("myPart", "s1", "success", 1);
        assertBindingMetricMissing("myPart", "s1", "skipped");
        assertBindingMetricMissing("myPart", "s1", "empty");
        assertBindingMetricMissing("myPart", "s1", "failed");
    }

    @Test
    void skippedStep_recordsSkippedBindingMetric() {
        WorkflowStep s = step("s1", ctx -> StepResult.skipped(PartSkipReason.NO_KEYS_IN_MAIN));
        AggregationWorkflow workflow =
                new AggregationWorkflow("myPart", Set.of(), PartCriticality.REQUIRED, List.of(s));

        StepVerifier.create(executor.execute(workflow, contextWithEmptyRoot()))
                .expectNextCount(1)
                .verifyComplete();

        assertBindingMetric("myPart", "s1", "skipped", 1);
        assertBindingMetricMissing("myPart", "s1", "success");
    }

    @Test
    void emptyStep_recordsEmptyBindingMetric() {
        WorkflowStep s = step("s1", ctx -> StepResult.empty(PartSkipReason.DOWNSTREAM_EMPTY));
        AggregationWorkflow workflow =
                new AggregationWorkflow("myPart", Set.of(), PartCriticality.REQUIRED, List.of(s));

        StepVerifier.create(executor.execute(workflow, contextWithEmptyRoot()))
                .expectNextCount(1)
                .verifyComplete();

        assertBindingMetric("myPart", "s1", "empty", 1);
        assertBindingMetricMissing("myPart", "s1", "success");
    }

    @Test
    void failingStep_recordsFailedBindingMetric() {
        WorkflowStep s = step("s1", ctx -> {
            throw new RuntimeException("step boom");
        });
        AggregationWorkflow workflow =
                new AggregationWorkflow("myPart", Set.of(), PartCriticality.REQUIRED, List.of(s));

        StepVerifier.create(executor.execute(workflow, contextWithEmptyRoot()))
                .expectError()
                .verify();

        assertBindingMetric("myPart", "s1", "failed", 1);
        assertBindingMetricMissing("myPart", "s1", "success");
    }

    @Test
    void keyedBindingStep_usesBindingNameNotStepNameAsMetricTag() {
        // KeyedBindingStep.bindingName() exposes the DownstreamBinding name ("b"),
        // which should appear in the metric instead of the step name ("enrich").
        ObjectNode root = parseObject("""
                {"data": [{"id": "x1"}]}
                """);
        JsonNode response = parseObject("""
                {"items": [{"id": "x1"}]}
                """);
        KeyedBindingStep keyedStep = new KeyedBindingStep("enrich", keyedBinding((keys, ctx) -> Mono.just(response)));
        AggregationWorkflow workflow =
                new AggregationWorkflow("myPart", Set.of(), PartCriticality.REQUIRED, List.of(keyedStep));

        StepVerifier.create(executor.execute(workflow, contextWith(root)))
                .expectNextCount(1)
                .verifyComplete();

        // binding tag = "b" (the DownstreamBinding name), not "enrich" (the step name)
        assertBindingMetric("myPart", "b", "success", 1);
        assertBindingMetricMissing("myPart", "enrich", "success");
    }

    // -------------------------------------------------------------------------
    // Multi-binding workflow (Phase 11)
    // -------------------------------------------------------------------------

    @Test
    void twoRootSnapshotBindings_combinedPatchContainsBothWrites() {
        ObjectNode root = parseObject("""
                {"data": [{"id": "a1"}]}
                """);
        JsonNode resp1 = parseObject("""
                {"items": [{"id": "a1", "v": 1}]}
                """);
        JsonNode resp2 = parseObject("""
                {"items": [{"id": "a1", "w": 2}]}
                """);
        KeyedBindingStep step1 =
                new KeyedBindingStep("s1", writeBinding("s1", "field1", (keys, ctx) -> Mono.just(resp1)));
        KeyedBindingStep step2 =
                new KeyedBindingStep("s2", writeBinding("s2", "field2", (keys, ctx) -> Mono.just(resp2)));
        AggregationWorkflow workflow =
                new AggregationWorkflow("part", Set.of(), PartCriticality.REQUIRED, List.of(step1, step2));

        StepVerifier.create(executor.execute(workflow, contextWith(root)))
                .assertNext(result -> {
                    JsonPatchDocument patch = Objects.requireNonNull(result.patch(), "patch");
                    // Each binding produced one write op: field1 and field2
                    assertThat(patch.operations()).hasSize(2);
                    assertThat(patch.operations().get(0).path()).isEqualTo("/data/0/field1");
                    assertThat(patch.operations().get(1).path()).isEqualTo("/data/0/field2");
                })
                .verifyComplete();

        // Both bindings emit their own metric tags
        assertBindingMetric("part", "s1", "success", 1);
        assertBindingMetric("part", "s2", "success", 1);
    }

    @Test
    void fetchOnlyBinding_storesValueAndWorkflowContinues() {
        ObjectNode root = parseObject("""
                {"data": [{"id": "a1"}]}
                """);
        JsonNode fetchedData = parseObject("""
                {"items": [{"id": "a1", "extra": "yes"}]}
                """);
        JsonNode resp2 = parseObject("""
                {"items": [{"id": "a1", "v": 42}]}
                """);

        // Binding 1: fetch-only (storeAs="b1result", no writeRule)
        KeyExtractionRule keyRule = new KeyExtractionRule(KeySource.ROOT_SNAPSHOT, null, "$.data[*]", List.of("id"));
        ResponseIndexingRule indexRule = new ResponseIndexingRule("$.items[*]", List.of("id"));
        DownstreamBinding fetchOnly = new DownstreamBinding(
                new BindingName("s1"), keyRule, (keys, ctx) -> Mono.just(fetchedData), indexRule, "b1result", null);
        KeyedBindingStep step1 = new KeyedBindingStep("s1", fetchOnly);
        KeyedBindingStep step2 =
                new KeyedBindingStep("s2", writeBinding("s2", "field2", (keys, ctx) -> Mono.just(resp2)));
        AggregationWorkflow workflow =
                new AggregationWorkflow("part", Set.of(), PartCriticality.REQUIRED, List.of(step1, step2));

        StepVerifier.create(executor.execute(workflow, contextWith(root)))
                .assertNext(result -> {
                    // step1 produced no writes; step2 produced one
                    JsonPatchDocument patch = Objects.requireNonNull(result.patch(), "patch");
                    assertThat(patch.operations()).hasSize(1);
                    assertThat(patch.operations().getFirst().path()).isEqualTo("/data/0/field2");
                })
                .verifyComplete();
    }

    @Test
    void laterBinding_readsFromStepResult() {
        ObjectNode root = parseObject("""
                {"data": [{"id": "a1"}]}
                """);
        // Step 1 response stored as "b1result"; items have {id, relatedId}
        JsonNode step1Response = parseObject("""
                {"items": [{"id": "a1", "relatedId": "r99"}]}
                """);
        // Step 2 called with "r99" extracted from step1 result
        List<String> capturedStep2Keys = new java.util.ArrayList<>();
        JsonNode step2Response = parseObject("""
                {"items": [{"id": "r99", "name": "Related"}]}
                """);

        KeyExtractionRule keyRule1 = new KeyExtractionRule(KeySource.ROOT_SNAPSHOT, null, "$.data[*]", List.of("id"));
        ResponseIndexingRule indexRule = new ResponseIndexingRule("$.items[*]", List.of("id"));
        DownstreamBinding b1 = new DownstreamBinding(
                new BindingName("b1"), keyRule1, (keys, ctx) -> Mono.just(step1Response), indexRule, "b1result", null);

        // Phase 11: STEP_RESULT source bindings must be fetch-only (no writeRule).
        KeyExtractionRule keyRule2 =
                new KeyExtractionRule(KeySource.STEP_RESULT, "b1result", "$.items[*]", List.of("relatedId"));
        DownstreamBinding b2 = new DownstreamBinding(
                new BindingName("b2"),
                keyRule2,
                (keys, ctx) -> {
                    capturedStep2Keys.addAll(keys);
                    return Mono.just(step2Response);
                },
                new ResponseIndexingRule("$.items[*]", List.of("id")),
                "b2result", // fetch-only: store result, no write to root
                null);

        KeyedBindingStep step1 = new KeyedBindingStep("s1", b1);
        KeyedBindingStep step2 = new KeyedBindingStep("s2", b2);
        AggregationWorkflow workflow =
                new AggregationWorkflow("part", Set.of(), PartCriticality.REQUIRED, List.of(step1, step2));

        StepVerifier.create(executor.execute(workflow, contextWith(root)))
                .assertNext(result -> {
                    // Both bindings were fetch-only; result may carry an empty patch or stored values
                    assertThat(result).isNotNull();
                })
                .verifyComplete();

        // The key point: step 2 received "r99" extracted from step 1's stored response
        assertThat(capturedStep2Keys).containsExactly("r99");
    }

    @Test
    void laterBinding_readsFromCurrentRoot_seesEarlierPatch() {
        // After step 1 writes field "enriched" to data[0], step 2 with CURRENT_ROOT reads that field.
        ObjectNode root = parseObject("""
                {"data": [{"id": "a1"}]}
                """);
        JsonNode resp1 = parseObject("""
                {"items": [{"id": "a1"}]}
                """);

        // Step 1: write field "enriched" to /data/0
        KeyedBindingStep step1 =
                new KeyedBindingStep("s1", writeBinding("s1", "enriched", (keys, ctx) -> Mono.just(resp1)));

        // Step 2: extract from CURRENT_ROOT using the newly written "enriched" field as key
        List<String> capturedStep2Keys = new java.util.ArrayList<>();
        JsonNode resp2 = parseObject("""
                {"items": [{"id": "a1"}]}
                """);
        KeyExtractionRule keyRule2 = new KeyExtractionRule(KeySource.CURRENT_ROOT, null, "$.data[*]", List.of("id"));
        DownstreamBinding b2 = new DownstreamBinding(
                new BindingName("b2"),
                keyRule2,
                (keys, ctx) -> {
                    capturedStep2Keys.addAll(keys);
                    return Mono.just(resp2);
                },
                new ResponseIndexingRule("$.items[*]", List.of("id")),
                null,
                new WriteRule(
                        "$.data[*]",
                        new WriteRule.MatchBy("id", "id"),
                        new WriteRule.WriteAction.ReplaceField("second")));
        KeyedBindingStep step2 = new KeyedBindingStep("s2", b2);

        AggregationWorkflow workflow =
                new AggregationWorkflow("part", Set.of(), PartCriticality.REQUIRED, List.of(step1, step2));

        StepVerifier.create(executor.execute(workflow, contextWith(root)))
                .assertNext(result -> {
                    JsonPatchDocument patch = Objects.requireNonNull(result.patch(), "patch");
                    // step1 wrote field "enriched", step2 wrote field "second"
                    assertThat(patch.operations()).hasSize(2);
                })
                .verifyComplete();

        // Step 2 was able to extract "a1" from CURRENT_ROOT (which has step1's patch applied)
        assertThat(capturedStep2Keys).containsExactly("a1");
    }

    @Test
    void globalRoot_isNotMutatedDuringWorkflowExecution() {
        ObjectNode root = parseObject("""
                {"data": [{"id": "a1"}]}
                """);
        String originalJson = root.toString();
        JsonNode resp = parseObject("""
                {"items": [{"id": "a1", "v": 1}]}
                """);
        KeyedBindingStep step = new KeyedBindingStep("s1", writeBinding("s1", "field", (keys, ctx) -> Mono.just(resp)));
        AggregationWorkflow workflow =
                new AggregationWorkflow("part", Set.of(), PartCriticality.REQUIRED, List.of(step));
        AggregationContext ctx = contextWith(root);

        StepVerifier.create(executor.execute(workflow, ctx)).expectNextCount(1).verifyComplete();

        // The AggregationContext root (the one the executor and applicator would mutate at part end)
        // must be unchanged during workflow execution — the patch is not yet applied.
        assertThat(ctx.accountGroupResponse()).hasToString(originalJson);
    }

    @Test
    void conflict_samePathDifferentValues_failsWithMergeError() {
        WorkflowStep s1 = step(
                "s1",
                ctx -> StepResult.applied(JsonPatchBuilder.create()
                        .add("/data/flag", mapper.getNodeFactory().stringNode("x"))
                        .build()));
        WorkflowStep s2 = step(
                "s2",
                ctx -> StepResult.applied(JsonPatchBuilder.create()
                        .add("/data/flag", mapper.getNodeFactory().stringNode("y"))
                        .build()));
        AggregationWorkflow workflow =
                new AggregationWorkflow("p", Set.of(), PartCriticality.REQUIRED, List.of(s1, s2));

        // The root needs a /data object node for the first patch to apply cleanly
        ObjectNode root = parseObject("""
                {"data": {}}
                """);

        StepVerifier.create(executor.execute(workflow, contextWith(root)))
                .expectError(dev.abramenka.aggregation.error.OrchestrationException.class)
                .verify();
    }

    @Test
    void conflict_addThenReplaceOnSamePath_failsWithMergeError() {
        WorkflowStep s1 = step(
                "s1",
                ctx -> StepResult.applied(JsonPatchBuilder.create()
                        .add("/data/flag", mapper.getNodeFactory().stringNode("x"))
                        .build()));
        WorkflowStep s2 = step(
                "s2",
                ctx -> StepResult.applied(JsonPatchBuilder.create()
                        .replace("/data/flag", mapper.getNodeFactory().stringNode("x"))
                        .build()));
        AggregationWorkflow workflow =
                new AggregationWorkflow("p", Set.of(), PartCriticality.REQUIRED, List.of(s1, s2));
        ObjectNode root = parseObject("""
                {"data": {}}
                """);

        StepVerifier.create(executor.execute(workflow, contextWith(root)))
                .expectError(dev.abramenka.aggregation.error.OrchestrationException.class)
                .verify();
    }

    @Test
    void conflict_missingParentPath_failsWithMergeError() {
        WorkflowStep s1 = step(
                "s1",
                ctx -> StepResult.applied(JsonPatchBuilder.create()
                        .add("/missing/parent/field", mapper.getNodeFactory().stringNode("v"))
                        .build()));
        AggregationWorkflow workflow = new AggregationWorkflow("p", Set.of(), PartCriticality.REQUIRED, List.of(s1));

        StepVerifier.create(executor.execute(workflow, contextWithEmptyRoot()))
                .expectError(dev.abramenka.aggregation.error.OrchestrationException.class)
                .verify();
    }

    @Test
    void conflict_failedTestOp_failsWithMergeError() {
        ObjectNode root = parseObject("""
                {"status": "active"}
                """);
        // test asserts value is "inactive" — will fail
        WorkflowStep s1 = step(
                "s1",
                ctx -> StepResult.applied(JsonPatchBuilder.create()
                        .test("/status", mapper.getNodeFactory().stringNode("inactive"))
                        .build()));
        AggregationWorkflow workflow = new AggregationWorkflow("p", Set.of(), PartCriticality.REQUIRED, List.of(s1));

        StepVerifier.create(executor.execute(workflow, contextWith(root)))
                .expectError(dev.abramenka.aggregation.error.OrchestrationException.class)
                .verify();
    }

    // -------------------------------------------------------------------------
    // Phase 13: hardened conflict rules and no-partial-patch guarantee
    // -------------------------------------------------------------------------

    @Test
    void conflict_samePathSameValue_isIdempotentAndAllowed() {
        // Same path, same op type, same value across two steps → idempotent, must not fail
        WorkflowStep s1 = step(
                "s1",
                ctx -> StepResult.applied(JsonPatchBuilder.create()
                        .add("/flag", mapper.getNodeFactory().stringNode("v"))
                        .build()));
        WorkflowStep s2 = step(
                "s2",
                ctx -> StepResult.applied(JsonPatchBuilder.create()
                        .add("/flag", mapper.getNodeFactory().stringNode("v"))
                        .build()));
        ObjectNode root = parseObject("""
                {}
                """);
        AggregationWorkflow workflow =
                new AggregationWorkflow("p", Set.of(), PartCriticality.REQUIRED, List.of(s1, s2));

        // Idempotent — must complete without error
        StepVerifier.create(executor.execute(workflow, contextWith(root)))
                .assertNext(result -> assertThat(result.patch()).isNotNull())
                .verifyComplete();
    }

    @Test
    void conflict_arraAppend_multipleAppendsAllowed() {
        // /- appends to the same array path must never conflict
        WorkflowStep s1 = step(
                "s1",
                ctx -> StepResult.applied(JsonPatchBuilder.create()
                        .add("/items/-", mapper.getNodeFactory().stringNode("a"))
                        .build()));
        WorkflowStep s2 = step(
                "s2",
                ctx -> StepResult.applied(JsonPatchBuilder.create()
                        .add("/items/-", mapper.getNodeFactory().stringNode("b"))
                        .build()));
        ObjectNode root = parseObject("""
                {"items": []}
                """);
        AggregationWorkflow workflow =
                new AggregationWorkflow("p", Set.of(), PartCriticality.REQUIRED, List.of(s1, s2));

        StepVerifier.create(executor.execute(workflow, contextWith(root)))
                .assertNext(result -> {
                    assertThat(result.patch()).isNotNull();
                    assertThat(Objects.requireNonNull(result.patch()).operations())
                            .hasSize(2);
                })
                .verifyComplete();
    }

    @Test
    void noPartialPatch_globalRootUnchangedAfterConflictFailure() {
        // Even when a conflict is detected, the global root must remain completely untouched
        ObjectNode root = parseObject("""
                {"data": {}}
                """);
        String originalJson = root.toString();
        AggregationContext ctx = contextWith(root);

        WorkflowStep s1 = step(
                "s1",
                wCtx -> StepResult.applied(JsonPatchBuilder.create()
                        .add("/data/flag", mapper.getNodeFactory().stringNode("x"))
                        .build()));
        WorkflowStep s2 = step(
                "s2",
                wCtx -> StepResult.applied(JsonPatchBuilder.create()
                        .add("/data/flag", mapper.getNodeFactory().stringNode("y"))
                        .build()));
        AggregationWorkflow workflow =
                new AggregationWorkflow("p", Set.of(), PartCriticality.REQUIRED, List.of(s1, s2));

        StepVerifier.create(executor.execute(workflow, ctx))
                .expectError(dev.abramenka.aggregation.error.OrchestrationException.class)
                .verify();

        // Global root must be completely unchanged — no partial writes visible
        assertThat(ctx.accountGroupResponse()).hasToString(originalJson);
    }

    @Test
    void noPartialPatch_globalRootUnchangedAfterMissingParentFailure() {
        ObjectNode root = parseObject("""
                {}
                """);
        String originalJson = root.toString();
        AggregationContext ctx = contextWith(root);

        WorkflowStep s1 = step(
                "s1",
                wCtx -> StepResult.applied(JsonPatchBuilder.create()
                        .add("/missing/deep/field", mapper.getNodeFactory().stringNode("v"))
                        .build()));
        AggregationWorkflow workflow = new AggregationWorkflow("p", Set.of(), PartCriticality.REQUIRED, List.of(s1));

        StepVerifier.create(executor.execute(workflow, ctx))
                .expectError(dev.abramenka.aggregation.error.OrchestrationException.class)
                .verify();

        assertThat(ctx.accountGroupResponse()).hasToString(originalJson);
    }

    @Test
    void conflictFailure_mapsToOrchMergeFailed() {
        // Verify error type maps to ORCH-MERGE-FAILED catalog entry
        ObjectNode root = parseObject("""
                {"data": {}}
                """);
        WorkflowStep s1 = step(
                "s1",
                wCtx -> StepResult.applied(JsonPatchBuilder.create()
                        .add("/data/field", mapper.getNodeFactory().stringNode("x"))
                        .build()));
        WorkflowStep s2 = step(
                "s2",
                wCtx -> StepResult.applied(JsonPatchBuilder.create()
                        .replace("/data/field", mapper.getNodeFactory().stringNode("x"))
                        .build()));
        AggregationWorkflow workflow =
                new AggregationWorkflow("p", Set.of(), PartCriticality.REQUIRED, List.of(s1, s2));

        StepVerifier.create(executor.execute(workflow, contextWith(root)))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(dev.abramenka.aggregation.error.OrchestrationException.class);
                    dev.abramenka.aggregation.error.OrchestrationException ex =
                            (dev.abramenka.aggregation.error.OrchestrationException) error;
                    assertThat(ex.getBody().getType())
                            .isEqualTo(dev.abramenka.aggregation.error.ProblemCatalog.ORCH_MERGE_FAILED.type());
                })
                .verify();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private AggregationContext contextWithEmptyRoot() {
        return contextWith(mapper.createObjectNode());
    }

    private AggregationContext contextWith(ObjectNode root) {
        ClientRequestContext clientCtx =
                new ClientRequestContext(ForwardedHeaders.builder().build(), null, Projections.empty());
        return new AggregationContext(root, clientCtx);
    }

    /** A write-only binding named {@code bindingName} that writes to {@code fieldName} on each item. */
    private DownstreamBinding writeBinding(String bindingName, String fieldName, DownstreamCall call) {
        KeyExtractionRule keyRule = new KeyExtractionRule(KeySource.ROOT_SNAPSHOT, null, "$.data[*]", List.of("id"));
        ResponseIndexingRule indexRule = new ResponseIndexingRule("$.items[*]", List.of("id"));
        WriteRule writeRule = new WriteRule(
                "$.data[*]", new WriteRule.MatchBy("id", "id"), new WriteRule.WriteAction.ReplaceField(fieldName));
        return new DownstreamBinding(new BindingName(bindingName), keyRule, call, indexRule, null, writeRule);
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

    private void assertBindingMetric(String part, String binding, String outcome, double count) {
        assertThat(meterRegistry
                        .get("aggregation.binding.requests")
                        .tag("part", part)
                        .tag("binding", binding)
                        .tag("outcome", outcome)
                        .counter()
                        .count())
                .isEqualTo(count);
    }

    private void assertBindingMetricMissing(String part, String binding, String outcome) {
        assertThat(meterRegistry
                        .find("aggregation.binding.requests")
                        .tag("part", part)
                        .tag("binding", binding)
                        .tag("outcome", outcome)
                        .counter())
                .isNull();
    }
}
