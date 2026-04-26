package dev.abramenka.aggregation.workflow;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.abramenka.aggregation.model.PartCriticality;
import dev.abramenka.aggregation.workflow.binding.BindingName;
import dev.abramenka.aggregation.workflow.binding.DownstreamBinding;
import dev.abramenka.aggregation.workflow.binding.KeyExtractionRule;
import dev.abramenka.aggregation.workflow.binding.KeySource;
import dev.abramenka.aggregation.workflow.binding.ResponseIndexingRule;
import dev.abramenka.aggregation.workflow.binding.WriteRule;
import dev.abramenka.aggregation.workflow.ownership.OwnedTarget;
import dev.abramenka.aggregation.workflow.ownership.WriteOwnership;
import dev.abramenka.aggregation.workflow.step.KeyedBindingStep;
import java.util.List;
import java.util.Set;
import reactor.core.publisher.Mono;

/**
 * Phase 13 — static write-ownership validation and hardened conflict rule tests.
 */
class WriteOwnershipValidationTest {

    // -------------------------------------------------------------------------
    // OwnedTarget and WriteOwnership construction
    // -------------------------------------------------------------------------

    @org.junit.jupiter.api.Test
    void ownedTarget_blankField_throws() {
        assertThatThrownBy(() -> new OwnedTarget("  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank");
    }

    @org.junit.jupiter.api.Test
    void writeOwnership_of_factory() {
        WriteOwnership wo = WriteOwnership.of("account1", "riskScore");
        assertThatCode(() -> {}).doesNotThrowAnyException();
        org.assertj.core.api.Assertions.assertThat(wo.contains("account1")).isTrue();
        org.assertj.core.api.Assertions.assertThat(wo.contains("riskScore")).isTrue();
        org.assertj.core.api.Assertions.assertThat(wo.contains("unknown")).isFalse();
    }

    // -------------------------------------------------------------------------
    // 10. Declared write ownership accepts valid writes
    // -------------------------------------------------------------------------

    @org.junit.jupiter.api.Test
    void workflowWithOwnership_stepWritesOwnedField_passesValidation() {
        KeyedBindingStep step = replaceStep("s1", "myField");
        AggregationWorkflow workflow = new AggregationWorkflow(
                "test", Set.of(), PartCriticality.REQUIRED, List.of(step), WriteOwnership.of("myField"));

        // WorkflowAggregationPart calls WorkflowDefinitionValidator.validate(workflow) in its
        // constructor; we call it directly here to test the static rule.
        assertThatCode(() -> WorkflowDefinitionValidator.validate(workflow)).doesNotThrowAnyException();
    }

    @org.junit.jupiter.api.Test
    void workflowWithNoOwnership_anyWritesAllowed() {
        // ownership == null → no checks
        KeyedBindingStep step = replaceStep("s1", "anyField");
        AggregationWorkflow workflow =
                new AggregationWorkflow("test", Set.of(), PartCriticality.REQUIRED, List.of(step));

        assertThatCode(() -> WorkflowDefinitionValidator.validate(workflow)).doesNotThrowAnyException();
    }

    // -------------------------------------------------------------------------
    // 11. Declared write ownership rejects obviously conflicting definitions
    // -------------------------------------------------------------------------

    @org.junit.jupiter.api.Test
    void workflowWithOwnership_stepWritesUndeclaredField_failsAtValidation() {
        KeyedBindingStep step = replaceStep("s1", "notInOwnership");
        AggregationWorkflow workflow = new AggregationWorkflow(
                "test", Set.of(), PartCriticality.REQUIRED, List.of(step), WriteOwnership.of("declaredField"));

        assertThatThrownBy(() -> WorkflowDefinitionValidator.validate(workflow))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not declared in WriteOwnership");
    }

    @org.junit.jupiter.api.Test
    void workflowWithOwnership_twoStepsSameField_failsAtValidation() {
        KeyedBindingStep step1 = replaceStep("s1", "sharedField");
        KeyedBindingStep step2 = replaceStep("s2", "sharedField");
        AggregationWorkflow workflow = new AggregationWorkflow(
                "test", Set.of(), PartCriticality.REQUIRED, List.of(step1, step2), WriteOwnership.of("sharedField"));

        assertThatThrownBy(() -> WorkflowDefinitionValidator.validate(workflow))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("conflicting write definition");
    }

    @org.junit.jupiter.api.Test
    void workflowWithOwnership_multipleStepsDifferentOwnedFields_passesValidation() {
        KeyedBindingStep step1 = replaceStep("s1", "field1");
        KeyedBindingStep step2 = replaceStep("s2", "field2");
        AggregationWorkflow workflow = new AggregationWorkflow(
                "test",
                Set.of(),
                PartCriticality.REQUIRED,
                List.of(step1, step2),
                WriteOwnership.of("field1", "field2"));

        assertThatCode(() -> WorkflowDefinitionValidator.validate(workflow)).doesNotThrowAnyException();
    }

    @org.junit.jupiter.api.Test
    void storeOnlyStep_noWrittenField_doesNotConflictWithOwnership() {
        // A fetch-only binding (writeRule=null) has empty writtenFieldName → ignored by validator
        KeyExtractionRule keyRule = new KeyExtractionRule(KeySource.ROOT_SNAPSHOT, null, "$.data[*]", List.of("id"));
        ResponseIndexingRule indexRule = new ResponseIndexingRule("$.items[*]", List.of("id"));
        DownstreamBinding storeOnly = new DownstreamBinding(
                new BindingName("b"), keyRule, (keys, ctx) -> Mono.empty(), indexRule, "stored", null);
        KeyedBindingStep step = new KeyedBindingStep("s1", storeOnly);

        // ownership declares only "someOtherField" — no conflict because s1 doesn't write
        AggregationWorkflow workflow = new AggregationWorkflow(
                "test", Set.of(), PartCriticality.REQUIRED, List.of(step), WriteOwnership.of("someOtherField"));

        assertThatCode(() -> WorkflowDefinitionValidator.validate(workflow)).doesNotThrowAnyException();
    }

    // -------------------------------------------------------------------------
    // writtenFieldName() on steps
    // -------------------------------------------------------------------------

    @org.junit.jupiter.api.Test
    void keyedBindingStep_replaceField_exposeWrittenFieldName() {
        KeyedBindingStep step = replaceStep("s1", "score");
        org.assertj.core.api.Assertions.assertThat(step.writtenFieldName())
                .isPresent()
                .hasValue("score");
    }

    @org.junit.jupiter.api.Test
    void keyedBindingStep_appendToArray_exposeWrittenFieldName() {
        KeyExtractionRule keyRule = new KeyExtractionRule(KeySource.ROOT_SNAPSHOT, null, "$.data[*]", List.of("id"));
        ResponseIndexingRule indexRule = new ResponseIndexingRule("$.items[*]", List.of("id"));
        WriteRule writeRule = new WriteRule(
                "$.data[*]", new WriteRule.MatchBy("id", "id"), new WriteRule.WriteAction.AppendToArray("owners1"));
        DownstreamBinding binding = new DownstreamBinding(
                new BindingName("b"), keyRule, (keys, ctx) -> Mono.empty(), indexRule, null, writeRule);
        KeyedBindingStep step = new KeyedBindingStep("s1", binding);

        org.assertj.core.api.Assertions.assertThat(step.writtenFieldName())
                .isPresent()
                .hasValue("owners1");
    }

    @org.junit.jupiter.api.Test
    void keyedBindingStep_storeOnly_emptyWrittenFieldName() {
        KeyExtractionRule keyRule = new KeyExtractionRule(KeySource.ROOT_SNAPSHOT, null, "$.data[*]", List.of("id"));
        ResponseIndexingRule indexRule = new ResponseIndexingRule("$.items[*]", List.of("id"));
        DownstreamBinding binding = new DownstreamBinding(
                new BindingName("b"), keyRule, (keys, ctx) -> Mono.empty(), indexRule, "stored", null);
        KeyedBindingStep step = new KeyedBindingStep("s1", binding);

        org.assertj.core.api.Assertions.assertThat(step.writtenFieldName()).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private static KeyedBindingStep replaceStep(String stepName, String fieldName) {
        KeyExtractionRule keyRule = new KeyExtractionRule(KeySource.ROOT_SNAPSHOT, null, "$.data[*]", List.of("id"));
        ResponseIndexingRule indexRule = new ResponseIndexingRule("$.items[*]", List.of("id"));
        WriteRule writeRule = new WriteRule(
                "$.data[*]", new WriteRule.MatchBy("id", "id"), new WriteRule.WriteAction.ReplaceField(fieldName));
        DownstreamBinding binding = new DownstreamBinding(
                new BindingName("b"), keyRule, (keys, ctx) -> Mono.empty(), indexRule, null, writeRule);
        return new KeyedBindingStep(stepName, binding);
    }
}
