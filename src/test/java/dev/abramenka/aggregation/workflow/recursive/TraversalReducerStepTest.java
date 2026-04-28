package dev.abramenka.aggregation.workflow.recursive;

import static org.assertj.core.api.Assertions.assertThat;

import dev.abramenka.aggregation.error.OrchestrationException;
import dev.abramenka.aggregation.error.ProblemCatalog;
import dev.abramenka.aggregation.model.AggregationContext;
import dev.abramenka.aggregation.model.ClientRequestContext;
import dev.abramenka.aggregation.model.ForwardedHeaders;
import dev.abramenka.aggregation.model.Projections;
import dev.abramenka.aggregation.patch.JsonPatchBuilder;
import dev.abramenka.aggregation.workflow.StepResult;
import dev.abramenka.aggregation.workflow.WorkflowContext;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class TraversalReducerStepTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void execute_readsTraversalResultFromStepResultAndDelegatesReducer() {
        WorkflowContext context = context("""
                {"data":[{"owners1":[{"entity":{"number":"E-1"}}]}]}
                """);
        JsonNode traversal = json("""
                {"groups":[]}
                """);
        JsonNode currentRoot = context.currentRoot();
        context.variables().put("traversal", traversal);
        AtomicReference<JsonNode> seenTraversal = new AtomicReference<>();
        AtomicReference<JsonNode> seenRoot = new AtomicReference<>();

        TraversalReducerStep step = new TraversalReducerStep("reduce", "traversal", (input, rootForWriteDecision) -> {
            seenTraversal.set(input);
            seenRoot.set(rootForWriteDecision);
            return JsonPatchBuilder.create().build();
        });

        StepVerifier.create(step.execute(context))
                .assertNext(result -> assertThat(result).isInstanceOf(StepResult.Applied.class))
                .verifyComplete();

        assertThat(seenTraversal.get()).isSameAs(traversal);
        assertThat(seenRoot.get()).isSameAs(currentRoot);
    }

    @Test
    void execute_returnsAppliedStepResultWithPatch() {
        WorkflowContext context = context("""
                {"data":[{"owners1":[{"entity":{"number":"E-1"}}]}]}
                """);
        context.variables().put("traversal", json("""
                {"groups":[]}
                """));
        TraversalReducerStep step = new TraversalReducerStep(
                "reduce", "traversal", (input, rootForWriteDecision) -> JsonPatchBuilder.create()
                        .add("/data/0/owners1/0/beneficialOwnersDetails", mapper.createArrayNode())
                        .build());

        StepVerifier.create(step.execute(context))
                .assertNext(result -> {
                    assertThat(result).isInstanceOf(StepResult.Applied.class);
                    StepResult.Applied applied = (StepResult.Applied) result;
                    assertThat(Objects.requireNonNull(applied.patch()).operations())
                            .hasSize(1);
                    assertThat(applied.storeAs()).isNull();
                    assertThat(applied.storedValue()).isNull();
                })
                .verifyComplete();
    }

    @Test
    void execute_missingStepResultFailsDeterministically() {
        WorkflowContext context = context("""
                {"data":[]}
                """);
        TraversalReducerStep step = new TraversalReducerStep(
                "reduce", "missingTraversal", (traversalResult, rootForWriteDecision) -> JsonPatchBuilder.create()
                        .build());

        StepVerifier.create(step.execute(context))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(OrchestrationException.class);
                    assertThat(((OrchestrationException) error).catalog())
                            .isEqualTo(ProblemCatalog.ORCH_INVARIANT_VIOLATED);
                })
                .verify();
    }

    @Test
    void execute_doesNotMutateRootSnapshotOrCurrentRootDirectly() {
        WorkflowContext context = context("""
                {"data":[{"owners1":[{"entity":{"number":"E-1"}}]}]}
                """);
        context.variables().put("traversal", json("""
                {"groups":[]}
                """));
        String beforeSnapshot = context.rootSnapshot().toString();
        String beforeCurrent = context.currentRoot().toString();
        TraversalReducerStep step = new TraversalReducerStep(
                "reduce", "traversal", (traversalResult, rootForWriteDecision) -> JsonPatchBuilder.create()
                        .build());

        StepVerifier.create(step.execute(context)).expectNextCount(1).verifyComplete();

        assertThat(context.rootSnapshot()).hasToString(beforeSnapshot);
        assertThat(context.currentRoot()).hasToString(beforeCurrent);
    }

    private WorkflowContext context(String rootJson) {
        ObjectNode root = json(rootJson);
        AggregationContext aggregationContext = new AggregationContext(
                root, new ClientRequestContext(ForwardedHeaders.builder().build(), null, Projections.empty()));
        return new WorkflowContext(aggregationContext, root);
    }

    private ObjectNode json(String raw) {
        try {
            return (ObjectNode) mapper.readTree(raw);
        } catch (Exception ex) {
            throw new IllegalArgumentException(ex);
        }
    }
}
