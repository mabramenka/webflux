package dev.abramenka.aggregation.part;

import dev.abramenka.aggregation.error.FacadeException;
import dev.abramenka.aggregation.error.OrchestrationException;
import dev.abramenka.aggregation.model.AggregationContext;
import dev.abramenka.aggregation.model.AggregationPart;
import dev.abramenka.aggregation.model.AggregationPartResult;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
@RequiredArgsConstructor
@Slf4j
class AggregationPartRunner {

    private static final String PART_OBSERVATION = "aggregation.part";
    private static final String PART_TAG = "part";

    private final AggregationPartMetrics metrics;
    private final ObservationRegistry observationRegistry;

    Mono<AggregationPartResult> execute(AggregationPart part, AggregationContext context) {
        return Mono.defer(() -> {
            Observation observation = Observation.start(PART_OBSERVATION, observationRegistry)
                    .lowCardinalityKeyValue(PART_TAG, part.name());
            return part.execute(context)
                    .switchIfEmpty(Mono.error(() ->
                            OrchestrationException.invariantViolated(new IllegalStateException("Aggregation part '"
                                    + part.name() + "' returned an empty Mono; parts must emit a result"))))
                    .doOnError(Exception.class, ex -> {
                        metrics.record(part.name(), "failure");
                        log.warn("Aggregation part '{}' failed", part.name(), ex);
                    })
                    .onErrorMap(ex -> !(ex instanceof FacadeException), OrchestrationException::invariantViolated)
                    .contextWrite(ctx -> ctx.put(ObservationThreadLocalAccessor.KEY, observation))
                    .doOnError(observation::error)
                    .doFinally(signalType -> observation.stop());
        });
    }
}
