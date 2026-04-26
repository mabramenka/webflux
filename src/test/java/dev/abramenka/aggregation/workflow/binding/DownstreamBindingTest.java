package dev.abramenka.aggregation.workflow.binding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.abramenka.aggregation.workflow.binding.WriteRule.WriteAction;
import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class DownstreamBindingTest {

    private static final DownstreamCall NO_CALL = (keys, ctx) -> Mono.empty();
    private static final KeyExtractionRule ROOT_EXTRACTION =
            new KeyExtractionRule(KeySource.ROOT_SNAPSHOT, null, "$.data[*]", List.of("id"));
    private static final ResponseIndexingRule RESPONSE_INDEX = new ResponseIndexingRule("$.data[*]", List.of("id"));
    private static final WriteRule WRITE_REPLACE =
            new WriteRule("$.data[*]", null, new WriteAction.ReplaceField("foo"));

    @Test
    void buildsWithStoreAsOnly() {
        DownstreamBinding binding = new DownstreamBinding(
                new BindingName("risk"), ROOT_EXTRACTION, NO_CALL, RESPONSE_INDEX, "riskResult", null);

        assertThat(binding.storeAs()).isEqualTo("riskResult");
        assertThat(binding.writeRule()).isNull();
    }

    @Test
    void buildsWithWriteRuleOnly() {
        DownstreamBinding binding = new DownstreamBinding(
                new BindingName("risk"), ROOT_EXTRACTION, NO_CALL, RESPONSE_INDEX, null, WRITE_REPLACE);

        assertThat(binding.writeRule()).isEqualTo(WRITE_REPLACE);
    }

    @Test
    void rejectsBindingThatProducesNeitherStepResultNorPatch() {
        BindingName name = new BindingName("risk");
        assertThatThrownBy(() -> new DownstreamBinding(name, ROOT_EXTRACTION, NO_CALL, RESPONSE_INDEX, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must produce a step result");
    }

    @Test
    void rejectsBlankStoreAs() {
        BindingName name = new BindingName("risk");
        assertThatThrownBy(() ->
                        new DownstreamBinding(name, ROOT_EXTRACTION, NO_CALL, RESPONSE_INDEX, "  ", WRITE_REPLACE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("storeAs");
    }

    @Test
    void bindingNameRejectsBlank() {
        assertThatThrownBy(() -> new BindingName(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank");
    }
}
