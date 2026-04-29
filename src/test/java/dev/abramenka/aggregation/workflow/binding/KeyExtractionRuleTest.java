package dev.abramenka.aggregation.workflow.binding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class KeyExtractionRuleTest {

    @Test
    void copiesKeyPathsDefensively() {
        List<String> input = new ArrayList<>(List.of("id"));
        KeyExtractionRule rule = new KeyExtractionRule(KeySource.ROOT_SNAPSHOT, null, "$.data[*]", input);

        input.add("mutated");

        assertThat(rule.keyPaths()).containsExactly("id");
    }

    @Test
    void stepResultRequiresStepName() {
        List<String> keyPaths = List.of("id");

        assertThatThrownBy(() -> new KeyExtractionRule(KeySource.STEP_RESULT, null, "$.data[*]", keyPaths))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("stepResultName");
    }

    @Test
    void stepResultAcceptsStepName() {
        KeyExtractionRule rule = new KeyExtractionRule(KeySource.STEP_RESULT, "bResult", "$.data[*]", List.of("id"));

        assertThat(rule.stepResultName()).isEqualTo("bResult");
    }

    @Test
    void stepResultNameRejectedForOtherSources() {
        List<String> keyPaths = List.of("id");

        assertThatThrownBy(() -> new KeyExtractionRule(KeySource.ROOT_SNAPSHOT, "bResult", "$.data[*]", keyPaths))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("only valid when source is STEP_RESULT");
    }

    @Test
    void rejectsBlankSourceItemPath() {
        List<String> keyPaths = List.of("id");

        assertThatThrownBy(() -> new KeyExtractionRule(KeySource.ROOT_SNAPSHOT, null, " ", keyPaths))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sourceItemPath");
    }

    @Test
    void rejectsEmptyKeyPaths() {
        List<String> keyPaths = List.of();

        assertThatThrownBy(() -> new KeyExtractionRule(KeySource.ROOT_SNAPSHOT, null, "$.data[*]", keyPaths))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("keyPaths");
    }

    @Test
    void rejectsBlankKeyPathEntry() {
        List<String> withBlank = new ArrayList<>();
        withBlank.add("id");
        withBlank.add(" ");
        assertThatThrownBy(() -> new KeyExtractionRule(KeySource.ROOT_SNAPSHOT, null, "$.data[*]", withBlank))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank");
    }
}
