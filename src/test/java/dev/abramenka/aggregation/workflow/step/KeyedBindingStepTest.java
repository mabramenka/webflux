package dev.abramenka.aggregation.workflow.step;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.abramenka.aggregation.error.DownstreamClientException;
import dev.abramenka.aggregation.model.AggregationContext;
import dev.abramenka.aggregation.model.ClientRequestContext;
import dev.abramenka.aggregation.model.ForwardedHeaders;
import dev.abramenka.aggregation.model.PartSkipReason;
import dev.abramenka.aggregation.model.Projections;
import dev.abramenka.aggregation.patch.JsonPatchDocument;
import dev.abramenka.aggregation.patch.JsonPatchOperation;
import dev.abramenka.aggregation.workflow.StepResult;
import dev.abramenka.aggregation.workflow.WorkflowContext;
import dev.abramenka.aggregation.workflow.binding.BindingName;
import dev.abramenka.aggregation.workflow.binding.DownstreamBinding;
import dev.abramenka.aggregation.workflow.binding.DownstreamCall;
import dev.abramenka.aggregation.workflow.binding.KeyExtractionRule;
import dev.abramenka.aggregation.workflow.binding.KeySource;
import dev.abramenka.aggregation.workflow.binding.ResponseIndexingRule;
import dev.abramenka.aggregation.workflow.binding.WriteRule;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class KeyedBindingStepTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    // -------------------------------------------------------------------------
    // Scenario 1: no keys from ROOT_SNAPSHOT → SKIPPED / NO_KEYS_IN_MAIN
    // -------------------------------------------------------------------------

    @Test
    void noKeysFromRootSnapshot_returnsSkipped() {
        ObjectNode root = parse("""
                {"data": []}
                """);
        KeyedBindingStep step = stepWithReplaceField(keys -> {
            throw new AssertionError("should not be called");
        });

        StepVerifier.create(step.execute(contextFor(root)))
                .assertNext(result -> {
                    assertThat(result).isInstanceOf(StepResult.Skipped.class);
                    assertThat(((StepResult.Skipped) result).reason()).isEqualTo(PartSkipReason.NO_KEYS_IN_MAIN);
                })
                .verifyComplete();
    }

    // -------------------------------------------------------------------------
    // Scenario 2: extracted duplicate keys are deduplicated before downstream call
    // -------------------------------------------------------------------------

    @Test
    void duplicateKeysAreDeduplicatedBeforeDownstreamCall() {
        ObjectNode root = parse("""
                {"data": [{"id": "a"}, {"id": "a"}, {"id": "b"}]}
                """);
        List<List<String>> capturedCalls = new ArrayList<>();
        KeyedBindingStep step = stepWithReplaceField(keys -> {
            capturedCalls.add(List.copyOf(keys));
            return Mono.just(emptyDataResponse());
        });

        StepVerifier.create(step.execute(contextFor(root))).expectNextCount(1).verifyComplete();

        assertThat(capturedCalls).hasSize(1);
        assertThat(capturedCalls.get(0)).containsExactly("a", "b");
    }

    // -------------------------------------------------------------------------
    // Scenario 3: downstream response indexed with fallback response key paths
    // -------------------------------------------------------------------------

    @Test
    void downstreamResponseIndexedWithFallbackResponseKeyPaths() {
        ObjectNode root = parse("""
                {"data": [{"id": "n1"}, {"id": "i2"}]}
                """);
        // Response items have "number" (primary) or "id" (fallback) as indexing keys
        JsonNode response = parse("""
                {"items": [{"number": "n1", "value": "v1"}, {"id": "i2", "value": "v2"}]}
                """);
        KeyExtractionRule keyRule = new KeyExtractionRule(KeySource.ROOT_SNAPSHOT, null, "$.data[*]", List.of("id"));
        ResponseIndexingRule indexRule = new ResponseIndexingRule("$.items[*]", List.of("number", "id"));
        WriteRule writeRule = new WriteRule(
                "$.data[*]", new WriteRule.MatchBy("id", "number"), new WriteRule.WriteAction.ReplaceField("details"));
        DownstreamBinding binding = new DownstreamBinding(
                new BindingName("test"), keyRule, keys -> Mono.just(response), indexRule, null, writeRule);
        KeyedBindingStep step = new KeyedBindingStep("step", binding);

        StepVerifier.create(step.execute(contextFor(root)))
                .assertNext(result -> {
                    assertThat(result).isInstanceOf(StepResult.Applied.class);
                    StepResult.Applied applied = (StepResult.Applied) result;
                    JsonPatchDocument patch = Objects.requireNonNull(applied.patch(), "patch");
                    // Both items should have been matched and patched via their respective key paths
                    assertThat(patch.operations()).hasSize(2);
                })
                .verifyComplete();
    }

    // -------------------------------------------------------------------------
    // Scenario 4: matched targets produce JsonPatchDocument writes (ReplaceField)
    // -------------------------------------------------------------------------

    @Test
    void matchedTargetsProduceJsonPatchDocumentWrites_newField() {
        ObjectNode root = parse("""
                {"data": [{"id": "a1"}, {"id": "a2"}]}
                """);
        JsonNode response = parse("""
                {"items": [{"id": "a1", "details": "d1"}, {"id": "a2", "details": "d2"}]}
                """);
        KeyedBindingStep step = stepWithReplaceField(keys -> Mono.just(response));

        StepVerifier.create(step.execute(contextFor(root)))
                .assertNext(result -> {
                    assertThat(result).isInstanceOf(StepResult.Applied.class);
                    JsonPatchDocument patch = Objects.requireNonNull(((StepResult.Applied) result).patch(), "patch");
                    assertThat(patch.operations()).hasSize(2);
                    // field does not exist → add operations
                    assertThat(patch.operations().get(0)).isInstanceOf(JsonPatchOperation.Add.class);
                    assertThat(patch.operations().get(0).path()).isEqualTo("/data/0/enriched");
                    assertThat(patch.operations().get(1)).isInstanceOf(JsonPatchOperation.Add.class);
                    assertThat(patch.operations().get(1).path()).isEqualTo("/data/1/enriched");
                })
                .verifyComplete();
    }

    @Test
    void matchedTargetsProduceJsonPatchDocumentWrites_existingField() {
        ObjectNode root = parse("""
                {"data": [{"id": "a1", "enriched": "old"}]}
                """);
        JsonNode response = parse("""
                {"items": [{"id": "a1", "details": "d1"}]}
                """);
        KeyedBindingStep step = stepWithReplaceField(keys -> Mono.just(response));

        StepVerifier.create(step.execute(contextFor(root)))
                .assertNext(result -> {
                    JsonPatchDocument patch = Objects.requireNonNull(((StepResult.Applied) result).patch(), "patch");
                    assertThat(patch.operations()).hasSize(1);
                    // field already exists → replace operation
                    assertThat(patch.operations().get(0)).isInstanceOf(JsonPatchOperation.Replace.class);
                    assertThat(patch.operations().get(0).path()).isEqualTo("/data/0/enriched");
                })
                .verifyComplete();
    }

    @Test
    void matchedTargetsProduceJsonPatchDocumentWrites_appendToArray() {
        ObjectNode item = mapper.createObjectNode();
        item.put("id", "a1");
        item.set("tags", mapper.createArrayNode()); // array exists
        ObjectNode root = mapper.createObjectNode();
        root.set("data", mapper.createArrayNode().add(item));

        JsonNode response = parse("""
                {"items": [{"id": "a1", "label": "lbl1"}]}
                """);
        KeyExtractionRule keyRule = new KeyExtractionRule(KeySource.ROOT_SNAPSHOT, null, "$.data[*]", List.of("id"));
        ResponseIndexingRule indexRule = new ResponseIndexingRule("$.items[*]", List.of("id"));
        WriteRule writeRule = new WriteRule(
                "$.data[*]", new WriteRule.MatchBy("id", "id"), new WriteRule.WriteAction.AppendToArray("tags"));
        DownstreamBinding binding = new DownstreamBinding(
                new BindingName("test"), keyRule, keys -> Mono.just(response), indexRule, null, writeRule);
        KeyedBindingStep step = new KeyedBindingStep("step", binding);

        StepVerifier.create(step.execute(contextFor(root)))
                .assertNext(result -> {
                    assertThat(result).isInstanceOf(StepResult.Applied.class);
                    JsonPatchDocument patch = Objects.requireNonNull(((StepResult.Applied) result).patch(), "patch");
                    assertThat(patch.operations()).hasSize(1);
                    assertThat(patch.operations().get(0)).isInstanceOf(JsonPatchOperation.Add.class);
                    assertThat(patch.operations().get(0).path()).isEqualTo("/data/0/tags/-");
                })
                .verifyComplete();
    }

    // -------------------------------------------------------------------------
    // Scenario 5: unmatched targets are dropped
    // -------------------------------------------------------------------------

    @Test
    void unmatchedTargetsAreDropped() {
        ObjectNode root = parse("""
                {"data": [{"id": "a1"}, {"id": "missing"}]}
                """);
        JsonNode response = parse("""
                {"items": [{"id": "a1", "details": "d1"}]}
                """);
        KeyedBindingStep step = stepWithReplaceField(keys -> Mono.just(response));

        StepVerifier.create(step.execute(contextFor(root)))
                .assertNext(result -> {
                    assertThat(result).isInstanceOf(StepResult.Applied.class);
                    JsonPatchDocument patch = Objects.requireNonNull(((StepResult.Applied) result).patch(), "patch");
                    // only the matched item produces a write; the unmatched one is silently dropped
                    assertThat(patch.operations()).hasSize(1);
                    assertThat(patch.operations().get(0).path()).isEqualTo("/data/0/enriched");
                })
                .verifyComplete();
    }

    // -------------------------------------------------------------------------
    // Scenario 6: empty downstream response → EMPTY / DOWNSTREAM_EMPTY
    // -------------------------------------------------------------------------

    @Test
    void emptyDownstreamResponse_returnsEmpty() {
        ObjectNode root = parse("""
                {"data": [{"id": "a1"}]}
                """);
        KeyedBindingStep step = stepWithReplaceField(keys -> Mono.empty());

        StepVerifier.create(step.execute(contextFor(root)))
                .assertNext(result -> {
                    assertThat(result).isInstanceOf(StepResult.Empty.class);
                    assertThat(((StepResult.Empty) result).reason()).isEqualTo(PartSkipReason.DOWNSTREAM_EMPTY);
                })
                .verifyComplete();
    }

    // -------------------------------------------------------------------------
    // Scenario 7: downstream 404 → EMPTY / DOWNSTREAM_NOT_FOUND
    // -------------------------------------------------------------------------

    @Test
    void downstream404_returnsDownstreamNotFound() {
        ObjectNode root = parse("""
                {"data": [{"id": "a1"}]}
                """);
        DownstreamClientException notFound =
                DownstreamClientException.upstreamStatus("test-client", HttpStatusCode.valueOf(404));
        KeyedBindingStep step = stepWithReplaceField(keys -> Mono.error(notFound));

        StepVerifier.create(step.execute(contextFor(root)))
                .assertNext(result -> {
                    assertThat(result).isInstanceOf(StepResult.Empty.class);
                    assertThat(((StepResult.Empty) result).reason()).isEqualTo(PartSkipReason.DOWNSTREAM_NOT_FOUND);
                })
                .verifyComplete();
    }

    @Test
    void downstreamNon404Error_propagates() {
        ObjectNode root = parse("""
                {"data": [{"id": "a1"}]}
                """);
        DownstreamClientException serverError =
                DownstreamClientException.upstreamStatus("test-client", HttpStatus.INTERNAL_SERVER_ERROR);
        KeyedBindingStep step = stepWithReplaceField(keys -> Mono.error(serverError));

        StepVerifier.create(step.execute(contextFor(root)))
                .expectError(DownstreamClientException.class)
                .verify();
    }

    // -------------------------------------------------------------------------
    // Scenario 8: unsupported KeySource fails explicitly at construction
    // -------------------------------------------------------------------------

    @Test
    void unsupportedKeySource_currentRoot_failsAtConstruction() {
        KeyExtractionRule rule = new KeyExtractionRule(KeySource.CURRENT_ROOT, null, "$.data[*]", List.of("id"));
        ResponseIndexingRule indexRule = new ResponseIndexingRule("$.items[*]", List.of("id"));
        WriteRule writeRule = new WriteRule(
                "$.data[*]", new WriteRule.MatchBy("id", "id"), new WriteRule.WriteAction.ReplaceField("f"));
        DownstreamBinding binding =
                new DownstreamBinding(new BindingName("b"), rule, keys -> Mono.empty(), indexRule, null, writeRule);

        assertThatThrownBy(() -> new KeyedBindingStep("step", binding))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ROOT_SNAPSHOT");
    }

    @Test
    void unsupportedKeySource_stepResult_failsAtConstruction() {
        KeyExtractionRule rule = new KeyExtractionRule(KeySource.STEP_RESULT, "prev", "$.data[*]", List.of("id"));
        ResponseIndexingRule indexRule = new ResponseIndexingRule("$.items[*]", List.of("id"));
        WriteRule writeRule = new WriteRule(
                "$.data[*]", new WriteRule.MatchBy("id", "id"), new WriteRule.WriteAction.ReplaceField("f"));
        DownstreamBinding binding =
                new DownstreamBinding(new BindingName("b"), rule, keys -> Mono.empty(), indexRule, null, writeRule);

        assertThatThrownBy(() -> new KeyedBindingStep("step", binding))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ROOT_SNAPSHOT");
    }

    @Test
    void unsupportedKeySource_traversalState_failsAtConstruction() {
        KeyExtractionRule rule = new KeyExtractionRule(KeySource.TRAVERSAL_STATE, null, "$.data[*]", List.of("id"));
        ResponseIndexingRule indexRule = new ResponseIndexingRule("$.items[*]", List.of("id"));
        WriteRule writeRule = new WriteRule(
                "$.data[*]", new WriteRule.MatchBy("id", "id"), new WriteRule.WriteAction.ReplaceField("f"));
        DownstreamBinding binding =
                new DownstreamBinding(new BindingName("b"), rule, keys -> Mono.empty(), indexRule, null, writeRule);

        assertThatThrownBy(() -> new KeyedBindingStep("step", binding))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ROOT_SNAPSHOT");
    }

    // -------------------------------------------------------------------------
    // Scenario 9: invalid binding definition fails fast at construction
    // -------------------------------------------------------------------------

    @Test
    @SuppressWarnings("NullAway")
    void nullBinding_failsAtConstruction() {
        assertThatThrownBy(() -> new KeyedBindingStep("step", null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void blankName_failsAtConstruction() {
        DownstreamBinding binding = validWriteBinding(keys -> Mono.empty());
        assertThatThrownBy(() -> new KeyedBindingStep("  ", binding))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank");
    }

    @Test
    void writeRuleWithoutMatchBy_failsAtConstruction() {
        KeyExtractionRule keyRule = new KeyExtractionRule(KeySource.ROOT_SNAPSHOT, null, "$.data[*]", List.of("id"));
        ResponseIndexingRule indexRule = new ResponseIndexingRule("$.items[*]", List.of("id"));
        // matchBy is null
        WriteRule writeRule = new WriteRule("$.data[*]", null, new WriteRule.WriteAction.ReplaceField("f"));
        DownstreamBinding binding =
                new DownstreamBinding(new BindingName("b"), keyRule, keys -> Mono.empty(), indexRule, null, writeRule);

        assertThatThrownBy(() -> new KeyedBindingStep("step", binding))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("matchBy");
    }

    // -------------------------------------------------------------------------
    // Scenario 10: patch contains no accidental writes when no matches exist
    // -------------------------------------------------------------------------

    @Test
    void noMatchesInResponse_returnsEmptyNotAppliedWithEmptyPatch() {
        ObjectNode root = parse("""
                {"data": [{"id": "a1"}]}
                """);
        // Response has data but keys do not overlap with the root
        JsonNode response = parse("""
                {"items": [{"id": "zzz", "details": "d"}]}
                """);
        KeyedBindingStep step = stepWithReplaceField(keys -> Mono.just(response));

        StepVerifier.create(step.execute(contextFor(root)))
                .assertNext(result -> {
                    // Must not be Applied — must be Empty to avoid silently no-op writes
                    assertThat(result).isInstanceOf(StepResult.Empty.class);
                    assertThat(((StepResult.Empty) result).reason()).isEqualTo(PartSkipReason.DOWNSTREAM_EMPTY);
                })
                .verifyComplete();
    }

    // -------------------------------------------------------------------------
    // Extra: AppendToArray fails fast when target array does not exist
    // -------------------------------------------------------------------------

    @Test
    void appendToArray_missingArrayField_failsWithOrchestrationException() {
        ObjectNode root = parse("""
                {"data": [{"id": "a1"}]}
                """); // no "tags" array
        JsonNode response = parse("""
                {"items": [{"id": "a1", "label": "lbl1"}]}
                """);
        KeyExtractionRule keyRule = new KeyExtractionRule(KeySource.ROOT_SNAPSHOT, null, "$.data[*]", List.of("id"));
        ResponseIndexingRule indexRule = new ResponseIndexingRule("$.items[*]", List.of("id"));
        WriteRule writeRule = new WriteRule(
                "$.data[*]", new WriteRule.MatchBy("id", "id"), new WriteRule.WriteAction.AppendToArray("tags"));
        DownstreamBinding binding = new DownstreamBinding(
                new BindingName("test"), keyRule, keys -> Mono.just(response), indexRule, null, writeRule);
        KeyedBindingStep step = new KeyedBindingStep("step", binding);

        StepVerifier.create(step.execute(contextFor(root)))
                .expectError(dev.abramenka.aggregation.error.OrchestrationException.class)
                .verify();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private KeyedBindingStep stepWithReplaceField(DownstreamCall call) {
        return new KeyedBindingStep("step", validWriteBinding(call));
    }

    private DownstreamBinding validWriteBinding(DownstreamCall call) {
        KeyExtractionRule keyRule = new KeyExtractionRule(KeySource.ROOT_SNAPSHOT, null, "$.data[*]", List.of("id"));
        ResponseIndexingRule indexRule = new ResponseIndexingRule("$.items[*]", List.of("id"));
        WriteRule writeRule = new WriteRule(
                "$.data[*]", new WriteRule.MatchBy("id", "id"), new WriteRule.WriteAction.ReplaceField("enriched"));
        return new DownstreamBinding(new BindingName("test"), keyRule, call, indexRule, null, writeRule);
    }

    private JsonNode emptyDataResponse() {
        return parse("""
                {"items": []}
                """);
    }

    private WorkflowContext contextFor(ObjectNode root) {
        ClientRequestContext clientCtx =
                new ClientRequestContext(ForwardedHeaders.builder().build(), null, Projections.empty());
        AggregationContext aggCtx = new AggregationContext(root, clientCtx);
        return new WorkflowContext(aggCtx, root);
    }

    private ObjectNode parse(String json) {
        try {
            return (ObjectNode) mapper.readTree(json);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    @SuppressWarnings("unused")
    private ArrayNode parseArray(String json) {
        try {
            return (ArrayNode) mapper.readTree(json);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
