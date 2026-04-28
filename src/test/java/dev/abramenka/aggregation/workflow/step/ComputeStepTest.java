package dev.abramenka.aggregation.workflow.step;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.abramenka.aggregation.error.DownstreamClientException;
import dev.abramenka.aggregation.error.OrchestrationException;
import dev.abramenka.aggregation.model.AggregationContext;
import dev.abramenka.aggregation.model.ClientRequestContext;
import dev.abramenka.aggregation.model.ForwardedHeaders;
import dev.abramenka.aggregation.model.Projections;
import dev.abramenka.aggregation.workflow.StepResult;
import dev.abramenka.aggregation.workflow.WorkflowContext;
import dev.abramenka.aggregation.workflow.compute.ComputationException;
import dev.abramenka.aggregation.workflow.compute.ComputationInput;
import dev.abramenka.aggregation.workflow.compute.WorkflowValues;
import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class ComputeStepTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    // -------------------------------------------------------------------------
    // 1. Read from ROOT_SNAPSHOT
    // -------------------------------------------------------------------------

    @Test
    void compute_canReadFromRootSnapshot() {
        ObjectNode root = parse("""
                {"score": 42}
                """);
        WorkflowContext ctx = contextFor(root);

        ComputeStep step =
                new ComputeStep("addBonus", "result", List.of(ComputationInput.fromRootSnapshot("snap")), values -> {
                    JsonNode snap = values.require("snap");
                    int score = snap.path("score").intValue();
                    return mapper.getNodeFactory().numberNode(score + 10);
                });

        StepVerifier.create(step.execute(ctx))
                .assertNext(result -> {
                    assertThat(result).isInstanceOf(StepResult.Applied.class);
                    StepResult.Applied applied = (StepResult.Applied) result;
                    assertThat(applied.storeAs()).isEqualTo("result");
                    assertThat(storedValue(applied).intValue()).isEqualTo(52);
                })
                .verifyComplete();
    }

    // -------------------------------------------------------------------------
    // 2. Read from CURRENT_ROOT
    // -------------------------------------------------------------------------

    @Test
    void compute_canReadFromCurrentRoot() {
        ObjectNode root = parse("""
                {"label": "current"}
                """);
        WorkflowContext ctx = contextFor(root);

        ComputeStep step = new ComputeStep(
                "readCurrent", "result", List.of(ComputationInput.fromCurrentRoot("cur")), values -> values.require(
                                "cur")
                        .path("label"));

        StepVerifier.create(step.execute(ctx))
                .assertNext(result -> {
                    StepResult.Applied applied = (StepResult.Applied) result;
                    assertThat(storedValue(applied).asString()).isEqualTo("current");
                })
                .verifyComplete();
    }

    // -------------------------------------------------------------------------
    // 3. Read from STEP_RESULT produced by an earlier step
    // -------------------------------------------------------------------------

    @Test
    void compute_canReadFromStepResult() {
        WorkflowContext ctx = contextFor(mapper.createObjectNode());
        // Pre-populate the variable store to simulate an earlier step's output
        ctx.variables().put("priorData", parse("""
                {"multiplier": 3}
                """));

        ComputeStep step = new ComputeStep(
                "multiply", "product", List.of(ComputationInput.fromStepResult("data", "priorData")), values -> {
                    int m = values.require("data").path("multiplier").intValue();
                    return mapper.getNodeFactory().numberNode(m * 7);
                });

        StepVerifier.create(step.execute(ctx))
                .assertNext(result -> {
                    StepResult.Applied applied = (StepResult.Applied) result;
                    assertThat(applied.storeAs()).isEqualTo("product");
                    assertThat(storedValue(applied).intValue()).isEqualTo(21);
                })
                .verifyComplete();
    }

    // -------------------------------------------------------------------------
    // 4. Stores named STEP_RESULT; 5. later step reads it
    // -------------------------------------------------------------------------

    @Test
    void compute_storesNamedResult_visibleToLaterStep() {
        WorkflowContext ctx = contextFor(mapper.createObjectNode());

        ComputeStep step = new ComputeStep("produce", "computed", List.of(), values -> mapper.getNodeFactory()
                .stringNode("hello"));

        StepVerifier.create(step.execute(ctx))
                .assertNext(result -> {
                    // drive the executor side-effect manually (variables stored by the executor)
                    StepResult.Applied applied = (StepResult.Applied) result;
                    ctx.variables().put(storeAs(applied), storedValue(applied));
                })
                .verifyComplete();

        // A later step can now read the stored result
        assertThat(ctx.variables().get("computed")).isPresent().hasValueSatisfying(v -> assertThat(v.asString())
                .isEqualTo("hello"));
    }

    // -------------------------------------------------------------------------
    // 6. Does not mutate global root
    // -------------------------------------------------------------------------

    @Test
    void compute_doesNotMutateGlobalRoot() {
        ObjectNode root = parse("""
                {"untouched": true}
                """);
        String originalJson = root.toString();
        AggregationContext aggCtx = aggCtx(root);
        WorkflowContext ctx = new WorkflowContext(aggCtx, root);

        ComputeStep step = new ComputeStep(
                "noMutation",
                "out",
                List.of(ComputationInput.fromRootSnapshot("snap")),
                values -> mapper.getNodeFactory().numberNode(99));

        StepVerifier.create(step.execute(ctx)).expectNextCount(1).verifyComplete();

        // Global root (AggregationContext.accountGroupResponse) must be unchanged
        assertThat(aggCtx.accountGroupResponse()).hasToString(originalJson);
    }

    // -------------------------------------------------------------------------
    // 7. Does not mutate ROOT_SNAPSHOT
    // -------------------------------------------------------------------------

    @Test
    void compute_doesNotMutateRootSnapshot() {
        ObjectNode root = parse("""
                {"field": "original"}
                """);
        WorkflowContext ctx = contextFor(root);
        String originalSnapshot = ctx.rootSnapshot().toString();

        ComputeStep step =
                new ComputeStep("readOnly", "out", List.of(ComputationInput.fromRootSnapshot("snap")), values -> {
                    // Computation only reads; it must not mutate the passed node
                    return mapper.getNodeFactory()
                            .stringNode(values.require("snap").path("field").asString());
                });

        StepVerifier.create(step.execute(ctx)).expectNextCount(1).verifyComplete();

        assertThat(ctx.rootSnapshot()).hasToString(originalSnapshot);
    }

    // -------------------------------------------------------------------------
    // 8. Does not apply patches — result is stored, not patched
    // -------------------------------------------------------------------------

    @Test
    void compute_producesStoredResultNotPatch() {
        WorkflowContext ctx = contextFor(mapper.createObjectNode());

        ComputeStep step = new ComputeStep(
                "noPatch", "out", List.of(), values -> mapper.getNodeFactory().numberNode(1));

        StepVerifier.create(step.execute(ctx))
                .assertNext(result -> {
                    assertThat(result).isInstanceOf(StepResult.Applied.class);
                    StepResult.Applied applied = (StepResult.Applied) result;
                    // patch must be null — result is stored, not written via patch
                    assertThat(applied.patch()).isNull();
                    assertThat(applied.storeAs()).isNotNull();
                    assertThat(applied.storedValue()).isNotNull();
                })
                .verifyComplete();
    }

    // -------------------------------------------------------------------------
    // Compute failure → ORCH-INVARIANT-VIOLATED
    // -------------------------------------------------------------------------

    @Test
    void compute_runtimeException_mapsToOrchestrationException() {
        WorkflowContext ctx = contextFor(mapper.createObjectNode());

        ComputeStep step = new ComputeStep("buggy", "out", List.of(), values -> {
            throw new ArithmeticException("division by zero");
        });

        StepVerifier.create(step.execute(ctx))
                .expectError(OrchestrationException.class)
                .verify();
    }

    @Test
    void compute_computationExceptionInvariant_mapsToOrchestrationException() {
        WorkflowContext ctx = contextFor(mapper.createObjectNode());

        ComputeStep step = new ComputeStep("buggy", "out", List.of(), values -> {
            throw ComputationException.invariant("logic error", null);
        });

        StepVerifier.create(step.execute(ctx))
                .expectError(OrchestrationException.class)
                .verify();
    }

    // -------------------------------------------------------------------------
    // Bad input → ENRICH-CONTRACT-VIOLATION
    // -------------------------------------------------------------------------

    @Test
    void compute_computationExceptionInputViolation_mapsToContractViolation() {
        WorkflowContext ctx = contextFor(mapper.createObjectNode());

        ComputeStep step = new ComputeStep("badInput", "out", List.of(), values -> {
            throw ComputationException.inputViolation("unexpected null field");
        });

        StepVerifier.create(step.execute(ctx))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(DownstreamClientException.class);
                    DownstreamClientException ex = (DownstreamClientException) error;
                    assertThat(ex.getBody().getType())
                            .isEqualTo(dev.abramenka.aggregation.error.ProblemCatalog.ENRICH_CONTRACT_VIOLATION.type());
                })
                .verify();
    }

    // -------------------------------------------------------------------------
    // WorkflowValues.require() throws when input missing → ENRICH-CONTRACT-VIOLATION
    // -------------------------------------------------------------------------

    @Test
    void workflowValues_require_missingInput_throwsInputViolation() {
        WorkflowValues values = new WorkflowValues(java.util.Map.of());
        assertThatThrownBy(() -> values.require("missing"))
                .isInstanceOf(ComputationException.class)
                .satisfies(e ->
                        assertThat(((ComputationException) e).isInputError()).isTrue());
    }

    // -------------------------------------------------------------------------
    // 12. STEP_RESULT missing at runtime → ORCH-INVARIANT-VIOLATED
    // -------------------------------------------------------------------------

    @Test
    void compute_stepResultMissing_failsWithOrchestrationException() {
        WorkflowContext ctx = contextFor(mapper.createObjectNode());
        // variable store is empty — "priorData" was never stored

        ComputeStep step = new ComputeStep(
                "dependsOnPrior",
                "out",
                List.of(ComputationInput.fromStepResult("data", "priorData")),
                values -> mapper.getNodeFactory().stringNode("unreachable"));

        StepVerifier.create(step.execute(ctx))
                .expectError(OrchestrationException.class)
                .verify();
    }

    // -------------------------------------------------------------------------
    // Construction validation
    // -------------------------------------------------------------------------

    @Test
    void construction_blankName_throws() {
        assertThatThrownBy(() -> new ComputeStep("  ", "out", List.of(), values -> mapper.createObjectNode()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }

    @Test
    void construction_blankStoreAs_throws() {
        assertThatThrownBy(() -> new ComputeStep("step", "  ", List.of(), values -> mapper.createObjectNode()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("storeAs");
    }

    @Test
    void computationInput_traversalState_rejectedAtConstruction() {
        assertThatThrownBy(() -> new ComputationInput(
                        "v", dev.abramenka.aggregation.workflow.binding.KeySource.TRAVERSAL_STATE, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("TRAVERSAL_STATE");
    }

    // -------------------------------------------------------------------------
    // Multiple inputs — all sources in one step
    // -------------------------------------------------------------------------

    @Test
    void compute_multipleInputSources_allResolvedCorrectly() {
        ObjectNode root = parse("""
                {"base": 100}
                """);
        WorkflowContext ctx = contextFor(root);
        ctx.variables().put("bonus", mapper.getNodeFactory().numberNode(5));

        ComputeStep step = new ComputeStep(
                "combine",
                "total",
                List.of(
                        ComputationInput.fromRootSnapshot("snap"),
                        ComputationInput.fromCurrentRoot("cur"),
                        ComputationInput.fromStepResult("bonus", "bonus")),
                values -> {
                    int base = values.require("snap").path("base").intValue();
                    int cur = values.require("cur").path("base").intValue();
                    int bonus = values.require("bonus").intValue();
                    return mapper.getNodeFactory().numberNode(base + cur + bonus);
                });

        StepVerifier.create(step.execute(ctx))
                .assertNext(result -> {
                    StepResult.Applied applied = (StepResult.Applied) result;
                    assertThat(storedValue(applied).intValue()).isEqualTo(205);
                })
                .verifyComplete();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private WorkflowContext contextFor(ObjectNode root) {
        return new WorkflowContext(aggCtx(root), root);
    }

    private AggregationContext aggCtx(ObjectNode root) {
        ClientRequestContext clientCtx =
                new ClientRequestContext(ForwardedHeaders.builder().build(), null, Projections.empty());
        return new AggregationContext(root, clientCtx);
    }

    private ObjectNode parse(String json) {
        try {
            return (ObjectNode) mapper.readTree(json);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String storeAs(StepResult.Applied applied) {
        String storeAs = applied.storeAs();
        if (storeAs == null) {
            throw new AssertionError("storeAs");
        }
        return storeAs;
    }

    private static JsonNode storedValue(StepResult.Applied applied) {
        JsonNode storedValue = applied.storedValue();
        if (storedValue == null) {
            throw new AssertionError("storedValue");
        }
        return storedValue;
    }
}
