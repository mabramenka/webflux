package dev.abramenka.aggregation.part.execution;

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

    void record(String partName, String outcome) {
        meterRegistry
                .counter(PART_REQUESTS_METRIC, PART_TAG, partName, OUTCOME_TAG, outcome)
                .increment();
    }
}
