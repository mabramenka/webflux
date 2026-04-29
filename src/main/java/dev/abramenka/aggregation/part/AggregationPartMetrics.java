package dev.abramenka.aggregation.part;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class AggregationPartMetrics {

    private static final String PART_REQUESTS_METRIC = "aggregation.part.requests";
    private static final String PART_TAG = "part";
    private static final String OUTCOME_TAG = "outcome";

    private final MeterRegistry meterRegistry;

    void recordOutcome(String partName, String outcome) {
        meterRegistry
                .counter(PART_REQUESTS_METRIC, PART_TAG, partName, OUTCOME_TAG, outcome)
                .increment();
    }
}
