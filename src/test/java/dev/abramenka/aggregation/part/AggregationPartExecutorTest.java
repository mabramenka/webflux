package dev.abramenka.aggregation.part;

import static org.assertj.core.api.Assertions.assertThat;

import dev.abramenka.aggregation.error.OrchestrationException;
import dev.abramenka.aggregation.model.AggregationContext;
import dev.abramenka.aggregation.model.AggregationPart;
import dev.abramenka.aggregation.model.AggregationPartPlan;
import dev.abramenka.aggregation.model.AggregationPartResult;
import dev.abramenka.aggregation.model.AggregationPartSelection;
import dev.abramenka.aggregation.model.AggregationResult;
import dev.abramenka.aggregation.model.ClientRequestContext;
import dev.abramenka.aggregation.model.ForwardedHeaders;
import dev.abramenka.aggregation.model.PartOutcome;
import dev.abramenka.aggregation.model.PartOutcomeStatus;
import dev.abramenka.aggregation.model.PartSkipReason;
import dev.abramenka.aggregation.model.Projections;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class AggregationPartExecutorTest {

    private final JsonMapper objectMapper = JsonMapper.builder().build();

    private SimpleMeterRegistry meterRegistry;
    private AggregationPartExecutor executor;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        AggregationPartMetrics metrics = new AggregationPartMetrics(meterRegistry);
        executor = new AggregationPartExecutor(
                new AggregationPartRunner(metrics),
                new AggregationRootFactory(),
                new AggregationPartResultApplicator(),
                metrics);
    }

    @Test
    void execute_failsWhenPartEmitsEmptyMono() {
        AggregationPart dependency = part("source", (root, context) -> Mono.empty());
        AggregationPart dependent = flagPart("dependent", "dependentRan", "source");

        StepVerifier.create(execute(dependency, dependent))
                .expectErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(OrchestrationException.class)
                        .hasMessage("The service detected an internal aggregation invariant violation."))
                .verify();

        assertPartMetric("source", "failure", 1);
        assertPartMetricMissing("dependent", "success");
    }

    @Test
    void execute_failsWhenSelectedDependencyFails() {
        AggregationPart dependency = part("source", (root, context) -> Mono.error(new IllegalStateException("down")));
        AggregationPart dependent = flagPart("dependent", "dependentRan", "source");

        StepVerifier.create(execute(dependency, dependent))
                .expectErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(OrchestrationException.class)
                        .hasMessage("The service detected an internal aggregation invariant violation."))
                .verify();

        assertPartMetric("source", "failure", 1);
        assertPartMetricMissing("dependent", "success");
    }

    @Test
    void execute_failsWhenSelectedPartReturnsResultForAnotherPart() {
        AggregationPart source = part("source", (root, context) -> Mono.just(flagResult("other", root, "sourceRan")));
        AggregationPart dependent = flagPart("dependent", "dependentRan", "source");

        StepVerifier.create(execute(source, dependent))
                .expectErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(OrchestrationException.class)
                        .hasMessage("The service detected an internal aggregation invariant violation."))
                .verify();

        assertPartMetric("source", "failure", 1);
        assertPartMetricMissing("source", "success");
        assertPartMetricMissing("dependent", "success");
    }

    @Test
    void execute_softSkipsUnsupportedPartAndCascadesToDependent() {
        AggregationPart dependency =
                part("source", context -> false, (root, context) -> Mono.just(flagResult("source", root, "sourceRan")));
        AggregationPart dependent = flagPart("dependent", "dependentRan", "source");

        StepVerifier.create(execute(dependency, dependent))
                .assertNext(result -> {
                    PartOutcome source = outcome(result, "source");
                    assertThat(source.status()).isEqualTo(PartOutcomeStatus.SKIPPED);
                    assertThat(source.reason()).isEqualTo(PartSkipReason.UNSUPPORTED_CONTEXT);
                    PartOutcome dependentOutcome = outcome(result, "dependent");
                    assertThat(dependentOutcome.status()).isEqualTo(PartOutcomeStatus.SKIPPED);
                    assertThat(dependentOutcome.reason()).isEqualTo(PartSkipReason.DEPENDENCY_EMPTY);
                    assertThat(result.data().has("sourceRan")).isFalse();
                    assertThat(result.data().has("dependentRan")).isFalse();
                })
                .verifyComplete();

        assertPartMetric("source", "skipped", 1);
        assertPartMetric("dependent", "skipped", 1);
        assertPartMetricMissing("source", "failure");
    }

    @Test
    void execute_softSkipsDependentWhenDependencyReturnsNoOp() {
        AggregationPart dependency = part(
                "source",
                (root, context) -> Mono.just(AggregationPartResult.empty("source", PartSkipReason.DOWNSTREAM_EMPTY)));
        AggregationPart dependent = flagPart("dependent", "dependentRan", "source");

        StepVerifier.create(execute(dependency, dependent))
                .assertNext(result -> {
                    PartOutcome source = outcome(result, "source");
                    assertThat(source.status()).isEqualTo(PartOutcomeStatus.EMPTY);
                    assertThat(source.reason()).isEqualTo(PartSkipReason.DOWNSTREAM_EMPTY);
                    PartOutcome dependentOutcome = outcome(result, "dependent");
                    assertThat(dependentOutcome.status()).isEqualTo(PartOutcomeStatus.SKIPPED);
                    assertThat(dependentOutcome.reason()).isEqualTo(PartSkipReason.DEPENDENCY_EMPTY);
                })
                .verifyComplete();

        assertPartMetric("source", "empty", 1);
        assertPartMetric("dependent", "skipped", 1);
    }

    @Test
    void execute_runsDependentPartAfterDependencyResultIsApplied() {
        AggregationPart dependency = flagPart("source", "sourceRan");
        AggregationPart dependent = part(
                "dependent",
                Set.of("source"),
                context -> context.accountGroupResponse().path("sourceRan").asBoolean(false),
                (root, context) -> Mono.just(flagResult("dependent", root, "dependentRan")));

        StepVerifier.create(execute(dependency, dependent))
                .assertNext(result -> {
                    assertThat(result.data().path("sourceRan").asBoolean()).isTrue();
                    assertThat(result.data().path("dependentRan").asBoolean()).isTrue();
                    assertThat(outcome(result, "source").status()).isEqualTo(PartOutcomeStatus.APPLIED);
                    assertThat(outcome(result, "dependent").status()).isEqualTo(PartOutcomeStatus.APPLIED);
                })
                .verifyComplete();

        assertPartMetric("source", "success", 1);
        assertPartMetric("dependent", "success", 1);
    }

    private static PartOutcome outcome(AggregationResult result, String partName) {
        return Objects.requireNonNull(
                result.partOutcomes().get(partName), () -> "missing outcome for part " + partName);
    }

    private Mono<AggregationResult> execute(AggregationPart dependency, AggregationPart dependent) {
        ObjectNode root = objectMapper.createObjectNode();
        AggregationPartPlan plan = new AggregationPartPlan(
                AggregationPartSelection.from(null),
                AggregationPartSelection.from(null),
                List.of(List.of(dependency), List.of(dependent)));
        return executor.execute("Account group", root, context(root), plan);
    }

    private AggregationContext context(ObjectNode root) {
        return new AggregationContext(
                root,
                new ClientRequestContext(ForwardedHeaders.builder().build(), null, Projections.empty()),
                AggregationPartSelection.from(null));
    }

    private static AggregationPart flagPart(String name, String flag, String... dependencies) {
        return part(
                name,
                Set.of(dependencies),
                context -> true,
                (root, context) -> Mono.just(flagResult(name, root, flag)));
    }

    private static AggregationPartResult flagResult(String name, ObjectNode rootSnapshot, String flag) {
        ObjectNode replacement = rootSnapshot.deepCopy();
        replacement.put(flag, true);
        return AggregationPartResult.patch(name, rootSnapshot, replacement);
    }

    private static AggregationPart part(
            String name, BiFunction<ObjectNode, AggregationContext, Mono<AggregationPartResult>> execute) {
        return part(name, Set.of(), context -> true, execute);
    }

    private static AggregationPart part(
            String name,
            Predicate<AggregationContext> supports,
            BiFunction<ObjectNode, AggregationContext, Mono<AggregationPartResult>> execute) {
        return part(name, Set.of(), supports, execute);
    }

    private static AggregationPart part(
            String name,
            Set<String> dependencies,
            Predicate<AggregationContext> supports,
            BiFunction<ObjectNode, AggregationContext, Mono<AggregationPartResult>> execute) {
        return new AggregationPart() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Set<String> dependencies() {
                return dependencies;
            }

            @Override
            public boolean supports(AggregationContext context) {
                return supports.test(context);
            }

            @Override
            public Mono<AggregationPartResult> execute(ObjectNode rootSnapshot, AggregationContext context) {
                return execute.apply(rootSnapshot, context);
            }
        };
    }

    private void assertPartMetric(String part, String outcome, double count) {
        assertThat(meterRegistry
                        .get("aggregation.part.requests")
                        .tag("part", part)
                        .tag("outcome", outcome)
                        .counter()
                        .count())
                .isEqualTo(count);
    }

    private void assertPartMetricMissing(String part, String outcome) {
        assertThat(meterRegistry
                        .find("aggregation.part.requests")
                        .tag("part", part)
                        .tag("outcome", outcome)
                        .counter())
                .isNull();
    }
}
