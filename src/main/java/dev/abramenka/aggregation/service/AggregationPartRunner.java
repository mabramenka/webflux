package dev.abramenka.aggregation.service;

import dev.abramenka.aggregation.model.AggregationContext;
import dev.abramenka.aggregation.model.AggregationPart;
import dev.abramenka.aggregation.model.AggregationPartResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import tools.jackson.databind.node.ObjectNode;

@Component
@RequiredArgsConstructor
@Slf4j
class AggregationPartRunner {

    private final AggregationPartMetrics metrics;

    Mono<AggregationPartResult> execute(AggregationPart part, ObjectNode rootSnapshot, AggregationContext context) {
        return part.execute(rootSnapshot, context)
                .doOnNext(result -> metrics.record(part.name(), "success"))
                .switchIfEmpty(Mono.defer(() -> {
                    metrics.record(part.name(), "empty");
                    log.warn("Optional aggregation part '{}' returned an empty result", part.name());
                    return Mono.<AggregationPartResult>empty();
                }))
                .onErrorResume(Exception.class, ex -> {
                    metrics.record(part.name(), "failure");
                    log.warn("Optional aggregation part '{}' failed and will be skipped", part.name(), ex);
                    return Mono.empty();
                });
    }
}
