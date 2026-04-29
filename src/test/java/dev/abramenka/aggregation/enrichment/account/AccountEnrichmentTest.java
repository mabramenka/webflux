package dev.abramenka.aggregation.enrichment.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import dev.abramenka.aggregation.client.Accounts;
import dev.abramenka.aggregation.model.AggregationPart;
import dev.abramenka.aggregation.model.PartCriticality;
import org.junit.jupiter.api.Test;

class AccountEnrichmentTest {

    @Test
    void metadata_matchesPublicWorkflowPart() {
        AggregationPart account =
                new AccountEnrichment(mock(Accounts.class), AccountEnrichmentTestFactory.noopWorkflowExecutor());

        assertThat(account.name()).isEqualTo("account");
        assertThat(account.base()).isFalse();
        assertThat(account.publicSelectable()).isTrue();
        assertThat(account.dependencies()).containsExactly("accountGroup");
        assertThat(account.criticality()).isEqualTo(PartCriticality.REQUIRED);
    }
}
