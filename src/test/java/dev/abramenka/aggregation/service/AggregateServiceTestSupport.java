package dev.abramenka.aggregation.service;

import static org.assertj.core.api.Assertions.assertThat;

import dev.abramenka.aggregation.client.AccountGroups;
import dev.abramenka.aggregation.client.Accounts;
import dev.abramenka.aggregation.client.Owners;
import dev.abramenka.aggregation.enrichment.account.AccountEnrichmentTestFactory;
import dev.abramenka.aggregation.enrichment.accountgroup.AccountGroupEnrichmentTestFactory;
import dev.abramenka.aggregation.enrichment.owners.OwnersEnrichmentTestFactory;
import dev.abramenka.aggregation.error.OrchestrationException;
import dev.abramenka.aggregation.model.AggregationContext;
import dev.abramenka.aggregation.model.AggregationPart;
import dev.abramenka.aggregation.model.AggregationPartResult;
import dev.abramenka.aggregation.model.ClientRequestContext;
import dev.abramenka.aggregation.model.ForwardedHeaders;
import dev.abramenka.aggregation.model.PartSkipReason;
import dev.abramenka.aggregation.model.Projections;
import dev.abramenka.aggregation.part.AggregationPartExecutor;
import dev.abramenka.aggregation.part.AggregationPartExecutorFactory;
import dev.abramenka.aggregation.part.AggregationPartPlanner;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@ExtendWith(MockitoExtension.class)
abstract class AggregateServiceTestSupport {

    protected final JsonMapper objectMapper = JsonMapper.builder().build();

    @Mock
    protected AccountGroups accountGroupClient;

    @Mock
    protected Accounts accountClient;

    @Mock
    protected Owners ownersClient;

    protected SimpleMeterRegistry meterRegistry;
    protected AggregateService aggregateService;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        aggregateService = new AggregateService(
                partPlanner(List.of(
                        AccountEnrichmentTestFactory.accountEnrichment(accountClient),
                        OwnersEnrichmentTestFactory.ownersEnrichment(ownersClient))),
                partExecutor(),
                ObservationRegistry.create(),
                objectMapper);
    }

    protected JsonNode json(String raw) {
        try {
            return objectMapper.readTree(raw);
        } catch (Exception ex) {
            throw new IllegalArgumentException(ex);
        }
    }

    protected ClientRequestContext clientRequestContext() {
        return new ClientRequestContext(ForwardedHeaders.builder().build(), null, Projections.empty());
    }

    protected AggregateService aggregateServiceWith(AggregationPart part) {
        return aggregateServiceWith(List.of(part));
    }

    protected AggregateService aggregateServiceWith(List<AggregationPart> parts) {
        return new AggregateService(partPlanner(parts), partExecutor(), ObservationRegistry.create(), objectMapper);
    }

    protected AggregationPartPlanner partPlanner(List<AggregationPart> parts) {
        List<AggregationPart> withBase = new ArrayList<>(parts.size() + 1);
        withBase.add(AccountGroupEnrichmentTestFactory.accountGroupEnrichment(accountGroupClient, objectMapper));
        withBase.addAll(parts);
        return new AggregationPartPlanner(withBase);
    }

    protected AggregationPartExecutor partExecutor() {
        return AggregationPartExecutorFactory.create(meterRegistry);
    }

    protected AggregationPart emptyEnrichment() {
        return new AggregationPart() {
            @Override
            public String name() {
                return "empty";
            }

            @Override
            public Mono<AggregationPartResult> execute(AggregationContext context) {
                return Mono.just(AggregationPartResult.empty(name(), PartSkipReason.DOWNSTREAM_EMPTY));
            }
        };
    }

    protected AggregationPart failingMergeEnrichment() {
        return new AggregationPart() {
            @Override
            public String name() {
                return "mergeFailure";
            }

            @Override
            public Mono<AggregationPartResult> execute(AggregationContext context) {
                return Mono.error(OrchestrationException.mergeFailed(new IllegalStateException("merge failed")));
            }
        };
    }

    protected AggregationPart dependentNoopEnrichment(String name, String dependency) {
        return new AggregationPart() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Set<String> dependencies() {
                return Set.of("accountGroup", dependency);
            }

            @Override
            public Mono<AggregationPartResult> execute(AggregationContext context) {
                return Mono.just(AggregationPartResult.patch(
                        name(),
                        context.accountGroupResponse(),
                        context.accountGroupResponse().deepCopy()));
            }
        };
    }

    protected AggregationPart rootFlagEnrichment(String name, String flag) {
        return new AggregationPart() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Mono<AggregationPartResult> execute(AggregationContext context) {
                ObjectNode working = context.accountGroupResponse().deepCopy();
                working.put(flag, true);
                return Mono.just(AggregationPartResult.patch(name(), context.accountGroupResponse(), working));
            }

            @Override
            public Set<String> dependencies() {
                return Set.of("accountGroup");
            }
        };
    }

    protected AggregationPart dependentSupportEnrichment(
            String name, String dependency, String requiredFlag, String targetFlag) {
        return new AggregationPart() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Set<String> dependencies() {
                return Set.of("accountGroup", dependency);
            }

            @Override
            public boolean supports(AggregationContext context) {
                return context.accountGroupResponse().path(requiredFlag).asBoolean(false);
            }

            @Override
            public Mono<AggregationPartResult> execute(AggregationContext context) {
                ObjectNode working = context.accountGroupResponse().deepCopy();
                working.put(targetFlag, true);
                return Mono.just(AggregationPartResult.patch(name(), context.accountGroupResponse(), working));
            }
        };
    }

    protected AggregationPart failingFetchEnrichment(String name) {
        return new AggregationPart() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Mono<AggregationPartResult> execute(AggregationContext context) {
                return Mono.error(new IllegalStateException("enrichment down"));
            }

            @Override
            public Set<String> dependencies() {
                return Set.of("accountGroup");
            }
        };
    }

    protected AggregationPart unsupportedEnrichment(String name) {
        return new AggregationPart() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public boolean supports(AggregationContext context) {
                return false;
            }

            @Override
            public Set<String> dependencies() {
                return Set.of("accountGroup");
            }

            @Override
            public Mono<AggregationPartResult> execute(AggregationContext context) {
                return Mono.just(AggregationPartResult.patch(
                        name(),
                        context.accountGroupResponse(),
                        context.accountGroupResponse().deepCopy()));
            }
        };
    }

    protected void assertPartMetric(String part, String outcome, double count) {
        assertThat(meterRegistry
                        .get("aggregation.part.requests")
                        .tag("part", part)
                        .tag("outcome", outcome)
                        .counter()
                        .count())
                .isEqualTo(count);
    }

    protected void assertPartMetricMissing(String part, String outcome) {
        assertThat(meterRegistry
                        .find("aggregation.part.requests")
                        .tag("part", part)
                        .tag("outcome", outcome)
                        .counter())
                .isNull();
    }
}
