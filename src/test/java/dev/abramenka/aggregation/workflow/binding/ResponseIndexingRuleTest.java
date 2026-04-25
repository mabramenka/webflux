package dev.abramenka.aggregation.workflow.binding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ResponseIndexingRuleTest {

    @Test
    void copiesResponseKeyPathsDefensively() {
        List<String> input = new ArrayList<>(List.of("id"));
        ResponseIndexingRule rule = new ResponseIndexingRule("$.data[*]", input);

        input.add("mutated");

        assertThat(rule.responseKeyPaths()).containsExactly("id");
    }

    @Test
    void rejectsBlankResponseItemPath() {
        assertThatThrownBy(() -> new ResponseIndexingRule(" ", List.of("id")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("responseItemPath");
    }

    @Test
    void rejectsEmptyResponseKeyPaths() {
        assertThatThrownBy(() -> new ResponseIndexingRule("$.data[*]", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("responseKeyPaths");
    }
}
