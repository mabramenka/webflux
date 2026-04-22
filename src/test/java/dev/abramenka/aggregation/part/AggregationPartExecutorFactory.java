package dev.abramenka.aggregation.part;

import io.micrometer.core.instrument.MeterRegistry;

public final class AggregationPartExecutorFactory {

    private AggregationPartExecutorFactory() {}

    public static AggregationPartExecutor create(MeterRegistry meterRegistry) {
        return new AggregationPartExecutor(
                new AggregationPartRunner(new AggregationPartMetrics(meterRegistry)),
                new AggregationRootFactory(),
                new AggregationPartResultApplicator());
    }
}
