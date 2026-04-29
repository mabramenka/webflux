package dev.abramenka.aggregation.enrichment.accountgroup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import dev.abramenka.aggregation.client.AccountGroups;
import dev.abramenka.aggregation.model.AggregationPart;
import dev.abramenka.aggregation.model.PartCriticality;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class AccountGroupEnrichmentTest {

    @Test
    void metadata_marksPartAsMandatoryInternalBase() {
        AggregationPart accountGroup = new AccountGroupEnrichment(
                mock(AccountGroups.class), JsonMapper.builder().build());

        assertThat(accountGroup.name()).isEqualTo("accountGroup");
        assertThat(accountGroup.base()).isTrue();
        assertThat(accountGroup.publicSelectable()).isFalse();
        assertThat(accountGroup.dependencies()).isEmpty();
        assertThat(accountGroup.criticality()).isEqualTo(PartCriticality.REQUIRED);
    }
}
