package dev.abramenka.aggregation.part;

import static org.assertj.core.api.Assertions.assertThat;

import dev.abramenka.aggregation.api.AggregateRequest;
import dev.abramenka.aggregation.error.DownstreamClientException;
import dev.abramenka.aggregation.model.AggregationContext;
import dev.abramenka.aggregation.model.AggregationPart;
import dev.abramenka.aggregation.model.AggregationPartPlan;
import dev.abramenka.aggregation.model.AggregationPartResult;
import dev.abramenka.aggregation.model.AggregationPartSelection;
import dev.abramenka.aggregation.model.AggregationResult;
import dev.abramenka.aggregation.model.ClientRequestContext;
import dev.abramenka.aggregation.model.ForwardedHeaders;
import dev.abramenka.aggregation.model.PartCriticality;
import dev.abramenka.aggregation.model.PartOutcome;
import dev.abramenka.aggregation.model.PartOutcomeStatus;
import dev.abramenka.aggregation.model.Projections;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.codec.DecodingException;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class AggregationPartFailurePolicyMatrixTest {

    private final JsonMapper objectMapper = JsonMapper.builder().build();
    private AggregationPartExecutor executor;

    @BeforeEach
    void setUp() {
        AggregationPartMetrics metrics = new AggregationPartMetrics(new SimpleMeterRegistry());
        executor = new AggregationPartExecutor(
                new AggregationPartRunner(ObservationRegistry.NOOP),
                new AggregationPartFailurePolicy(),
                new AggregationPartResultApplicator(),
                metrics);
    }

    @ParameterizedTest(name = "{0} - {1}")
    @MethodSource("failureMatrix")
    void failureOutcome_isDrivenByCriticalityAndFailureKind(
            String failureKind, Throwable failure, String expectedErrorCode, PartCriticality criticality) {
        AggregationPart part = failingPart("owners", criticality, failure);

        Mono<AggregationResult> execution = execute(part);
        if (criticality == PartCriticality.REQUIRED) {
            StepVerifier.create(execution)
                    .expectErrorSatisfies(error -> {
                        assertThat(error).isInstanceOf(DownstreamClientException.class);
                        DownstreamClientException downstreamError = (DownstreamClientException) error;
                        assertThat(downstreamError.catalog().errorCode()).isEqualTo(expectedErrorCode);
                        assertThat(downstreamError.getBody().getProperties())
                                .containsEntry("part", "owners")
                                .containsEntry("criticality", PartCriticality.REQUIRED.name());
                    })
                    .verify();
            return;
        }

        StepVerifier.create(execution)
                .assertNext(result -> {
                    PartOutcome outcome =
                            Objects.requireNonNull(result.partOutcomes().get("owners"));
                    assertThat(outcome.status()).isEqualTo(PartOutcomeStatus.FAILED);
                    assertThat(outcome.criticality()).isEqualTo(PartCriticality.OPTIONAL);
                    assertThat(outcome.errorCode()).isEqualTo(expectedErrorCode);
                    assertThat(outcome.reason()).isNotNull();
                })
                .verifyComplete();
    }

    private Mono<AggregationResult> execute(AggregationPart part) {
        ObjectNode root = objectMapper.createObjectNode();
        AggregationPartPlan plan = new AggregationPartPlan(
                AggregationPartSelection.from(null), AggregationPartSelection.from(null), List.of(List.of(part)));
        ClientRequestContext clientRequestContext =
                new ClientRequestContext(ForwardedHeaders.builder().build(), null, Projections.empty());
        return executor.execute(root, clientRequestContext, request(), plan);
    }

    private static AggregateRequest request() {
        return new AggregateRequest(List.of("AB123456789"), null);
    }

    private static AggregationPart failingPart(String name, PartCriticality criticality, Throwable failure) {
        return new AggregationPart() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public PartCriticality criticality() {
                return criticality;
            }

            @Override
            public Mono<AggregationPartResult> execute(AggregationContext context) {
                return Mono.error(failure);
            }
        };
    }

    private static Stream<Arguments> failureMatrix() {
        return Stream.of(
                Arguments.of(
                        "timeout",
                        DownstreamClientException.transport("Owners", new TimeoutException("timed out")),
                        "ENRICH-TIMEOUT",
                        PartCriticality.REQUIRED),
                Arguments.of(
                        "timeout",
                        DownstreamClientException.transport("Owners", new TimeoutException("timed out")),
                        "ENRICH-TIMEOUT",
                        PartCriticality.OPTIONAL),
                Arguments.of(
                        "invalid payload",
                        DownstreamClientException.transport("Owners", new DecodingException("bad payload")),
                        "ENRICH-INVALID-PAYLOAD",
                        PartCriticality.REQUIRED),
                Arguments.of(
                        "invalid payload",
                        DownstreamClientException.transport("Owners", new DecodingException("bad payload")),
                        "ENRICH-INVALID-PAYLOAD",
                        PartCriticality.OPTIONAL),
                Arguments.of(
                        "5xx",
                        DownstreamClientException.upstreamStatus("Owners", HttpStatus.INTERNAL_SERVER_ERROR),
                        "ENRICH-BAD-RESPONSE",
                        PartCriticality.REQUIRED),
                Arguments.of(
                        "5xx",
                        DownstreamClientException.upstreamStatus("Owners", HttpStatus.INTERNAL_SERVER_ERROR),
                        "ENRICH-BAD-RESPONSE",
                        PartCriticality.OPTIONAL));
    }
}
