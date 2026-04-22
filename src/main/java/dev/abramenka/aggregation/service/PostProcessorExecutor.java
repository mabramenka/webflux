package dev.abramenka.aggregation.service;

import dev.abramenka.aggregation.model.AggregationContext;
import dev.abramenka.aggregation.postprocessor.AggregationPostProcessor;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import tools.jackson.databind.node.ObjectNode;

@Component
@RequiredArgsConstructor
@Slf4j
class PostProcessorExecutor {

    private final MeterRegistry meterRegistry;

    Mono<Void> apply(AggregationPostProcessor postProcessor, ObjectNode root, AggregationContext context) {
        return postProcessor
                .apply(root, context)
                .doOnSuccess(unused -> record(postProcessor.name(), "success"))
                .onErrorResume(Exception.class, ex -> {
                    record(postProcessor.name(), "failure");
                    log.warn(
                            "Optional aggregation post-processor '{}' failed and will be skipped",
                            postProcessor.name(),
                            ex);
                    return Mono.empty();
                });
    }

    private void record(String partName, String outcome) {
        meterRegistry
                .counter("aggregation.part.requests", "part", partName, "outcome", outcome)
                .increment();
    }
}
