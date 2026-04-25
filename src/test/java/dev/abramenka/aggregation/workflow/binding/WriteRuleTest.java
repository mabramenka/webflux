package dev.abramenka.aggregation.workflow.binding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.abramenka.aggregation.workflow.binding.WriteRule.MatchBy;
import dev.abramenka.aggregation.workflow.binding.WriteRule.WriteAction;
import org.junit.jupiter.api.Test;

class WriteRuleTest {

    @Test
    void buildsWithoutMatchBy() {
        WriteRule rule = new WriteRule("$.data[*]", null, new WriteAction.ReplaceField("score"));

        assertThat(rule.matchBy()).isNull();
        assertThat(rule.action()).isInstanceOf(WriteAction.ReplaceField.class);
    }

    @Test
    void buildsWithMatchByAndAppendAction() {
        MatchBy matchBy = new MatchBy("accounts[*].id", "accountId");
        WriteRule rule = new WriteRule("$.data[*]", matchBy, new WriteAction.AppendToArray("account1"));

        assertThat(rule.matchBy()).isEqualTo(matchBy);
        assertThat(rule.action()).isInstanceOf(WriteAction.AppendToArray.class);
        assertThat(rule.action().fieldName()).isEqualTo("account1");
    }

    @Test
    void rejectsBlankTargetItemPath() {
        assertThatThrownBy(() -> new WriteRule(" ", null, new WriteAction.ReplaceField("x")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("targetItemPath");
    }

    @Test
    void rejectsBlankMatchByPaths() {
        assertThatThrownBy(() -> new MatchBy("", "responseId"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank");
    }

    @Test
    void rejectsBlankActionFieldName() {
        assertThatThrownBy(() -> new WriteAction.ReplaceField(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fieldName");
    }
}
