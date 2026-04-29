package dev.abramenka.aggregation.workflow;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Records internal binding-level metrics for workflow-based aggregation parts.
 *
 * <p>Emits {@code aggregation.binding.requests} with tags:
 *
 * <ul>
 *   <li>{@code part} — workflow (business part) name
 *   <li>{@code binding} — downstream binding name within the workflow
 *   <li>{@code outcome} — {@code success}, {@code empty}, {@code skipped}, or {@code failed}
 * </ul>
 *
 * <p>These are internal counters only. Binding details must not appear in public response bodies or
 * RFC 9457 problem details.
 */
@Component
@RequiredArgsConstructor
public class WorkflowBindingMetrics {

    private static final String BINDING_REQUESTS_METRIC = "aggregation.binding.requests";
    private static final String PART_TAG = "part";
    private static final String BINDING_TAG = "binding";
    private static final String OUTCOME_TAG = "outcome";

    private final MeterRegistry meterRegistry;

    void recordOutcome(String partName, String bindingName, String outcome) {
        meterRegistry
                .counter(BINDING_REQUESTS_METRIC, PART_TAG, partName, BINDING_TAG, bindingName, OUTCOME_TAG, outcome)
                .increment();
    }
}
