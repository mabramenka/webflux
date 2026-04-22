package dev.abramenka.aggregation.part;

import io.micrometer.core.instrument.MeterRegistry;

public final class AggregationPartExecutorFactory {

    private AggregationPartExecutorFactory() {}

    public static AggregationPartExecutor create(MeterRegistry meterRegistry) {
        AggregationPartMetrics metrics = new AggregationPartMetrics(meterRegistry);
        return new AggregationPartExecutor(
                new AggregationPartRunner(metrics),
                new AggregationRootFactory(),
                new AggregationPartResultApplicator(),
                metrics);
    }
}
