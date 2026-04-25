package dev.abramenka.aggregation.workflow.binding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.abramenka.aggregation.patch.JsonPatchBuilder;
import dev.abramenka.aggregation.patch.JsonPatchDocument;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class BindingResultTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void successWithPatchOnly() {
        JsonPatchDocument patch = JsonPatchBuilder.create().build();

        BindingResult result = BindingResult.success(new BindingName("b"), patch, null);

        assertThat(result.outcome()).isEqualTo(BindingOutcome.SUCCESS);
        assertThat(result.patch()).isEqualTo(patch);
        assertThat(result.stepValue()).isNull();
        assertThat(result.reason()).isNull();
    }

    @Test
    void successWithStepValueOnly() {
        JsonNode value = mapper.createObjectNode().put("answer", 42);

        BindingResult result = BindingResult.success(new BindingName("b"), null, value);

        assertThat(result.stepValue()).isSameAs(value);
    }

    @Test
    void successRequiresPatchOrStepValue() {
        BindingName name = new BindingName("b");
        assertThatThrownBy(() -> BindingResult.success(name, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("patch, a step value, or both");
    }

    @Test
    void emptyAndSkippedAndFailedCarryReason() {
        BindingName name = new BindingName("b");

        assertThat(BindingResult.empty(name, "DOWNSTREAM_EMPTY").reason()).isEqualTo("DOWNSTREAM_EMPTY");
        assertThat(BindingResult.skipped(name, "NO_KEYS_IN_MAIN").outcome()).isEqualTo(BindingOutcome.SKIPPED);
        assertThat(BindingResult.failed(name, "TIMEOUT").outcome()).isEqualTo(BindingOutcome.FAILED);
    }

    @Test
    void nonSuccessOutcomeRequiresReason() {
        BindingName name = new BindingName("b");
        assertThatThrownBy(() -> new BindingResult(name, BindingOutcome.EMPTY, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reason");
    }
}
