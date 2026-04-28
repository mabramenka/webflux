package dev.abramenka.aggregation.enrichment.account;

import dev.abramenka.aggregation.client.Accounts;
import dev.abramenka.aggregation.model.AggregationPart;
import dev.abramenka.aggregation.workflow.WorkflowBindingMetrics;
import dev.abramenka.aggregation.workflow.WorkflowExecutor;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

public final class AccountEnrichmentTestFactory {

    private AccountEnrichmentTestFactory() {}

    public static AggregationPart accountEnrichment(Accounts accountClient) {
        return new AccountEnrichment(accountClient, noopWorkflowExecutor());
    }

    /** Creates a {@link WorkflowExecutor} backed by a non-exporting meter registry for use in tests. */
    public static WorkflowExecutor noopWorkflowExecutor() {
        return new WorkflowExecutor(new WorkflowBindingMetrics(new SimpleMeterRegistry()));
    }
}
