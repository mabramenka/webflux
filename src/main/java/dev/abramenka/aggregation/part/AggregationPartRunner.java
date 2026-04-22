package dev.abramenka.aggregation.part;

import dev.abramenka.aggregation.error.FacadeException;
import dev.abramenka.aggregation.error.OrchestrationException;
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
                .doOnError(Exception.class, ex -> {
                    metrics.record(part.name(), "failure");
                    log.warn("Required aggregation part '{}' failed", part.name(), ex);
                })
                .switchIfEmpty(Mono.defer(() -> {
                    metrics.record(part.name(), "empty");
                    return Mono.error(OrchestrationException.invariantViolated(
                            new IllegalStateException("Required aggregation part returned an empty result")));
                }))
                .onErrorMap(ex -> !(ex instanceof FacadeException), OrchestrationException::invariantViolated);
    }
}
