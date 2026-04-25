package dev.abramenka.aggregation.service;

import static org.assertj.core.api.Assertions.assertThat;

import dev.abramenka.aggregation.client.AccountGroups;
import dev.abramenka.aggregation.client.Accounts;
import dev.abramenka.aggregation.client.Owners;
import dev.abramenka.aggregation.enrichment.account.AccountEnrichmentTestFactory;
import dev.abramenka.aggregation.enrichment.owners.OwnersEnrichmentTestFactory;
import dev.abramenka.aggregation.model.AggregationContext;
import dev.abramenka.aggregation.model.AggregationEnrichment;
import dev.abramenka.aggregation.model.AggregationPart;
import dev.abramenka.aggregation.model.ClientRequestContext;
import dev.abramenka.aggregation.model.ForwardedHeaders;
import dev.abramenka.aggregation.model.Projections;
import dev.abramenka.aggregation.part.AggregationPartExecutor;
import dev.abramenka.aggregation.part.AggregationPartExecutorFactory;
import dev.abramenka.aggregation.part.AggregationPartPlanner;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
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
                accountGroupClient,
                partPlanner(List.of(
                        AccountEnrichmentTestFactory.accountEnrichment(accountClient, objectMapper),
                        OwnersEnrichmentTestFactory.ownersEnrichment(ownersClient, objectMapper))),
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
        return new AggregateService(
                accountGroupClient, partPlanner(parts), partExecutor(), ObservationRegistry.create(), objectMapper);
    }

    protected AggregationPartPlanner partPlanner(List<AggregationPart> parts) {
        return new AggregationPartPlanner(parts);
    }

    protected AggregationPartExecutor partExecutor() {
        return AggregationPartExecutorFactory.create(meterRegistry);
    }

    protected AggregationEnrichment emptyEnrichment() {
        return new AggregationEnrichment() {
            @Override
            public String name() {
                return "empty";
            }

            @Override
            public Mono<JsonNode> fetch(AggregationContext context) {
                return Mono.empty();
            }

            @Override
            public void merge(ObjectNode root, JsonNode enrichmentResponse) {
                throw new IllegalStateException("Empty enrichment should not be merged");
            }
        };
    }

    protected AggregationEnrichment failingMergeEnrichment() {
        return new AggregationEnrichment() {
            @Override
            public String name() {
                return "mergeFailure";
            }

            @Override
            public Mono<JsonNode> fetch(AggregationContext context) {
                ObjectNode response = objectMapper.createObjectNode();
                response.put("status", "ok");
                return Mono.just(response);
            }

            @Override
            public void merge(ObjectNode root, JsonNode enrichmentResponse) {
                root.put("failedMergeWasAttempted", true);
                throw new IllegalStateException("merge failed");
            }
        };
    }

    protected AggregationEnrichment dependentNoopEnrichment(String name, String dependency) {
        return new AggregationEnrichment() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Set<String> dependencies() {
                return Set.of(dependency);
            }

            @Override
            public Mono<JsonNode> fetch(AggregationContext context) {
                return Mono.just(objectMapper.createObjectNode());
            }

            @Override
            public void merge(ObjectNode root, JsonNode enrichmentResponse) {}
        };
    }

    protected AggregationEnrichment rootFlagEnrichment(String name, String flag) {
        return new AggregationEnrichment() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Mono<JsonNode> fetch(AggregationContext context) {
                return Mono.just(objectMapper.createObjectNode());
            }

            @Override
            public void merge(ObjectNode root, JsonNode enrichmentResponse) {
                root.put(flag, true);
            }
        };
    }

    protected AggregationEnrichment dependentSupportEnrichment(
            String name, String dependency, String requiredFlag, String targetFlag) {
        return new AggregationEnrichment() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Set<String> dependencies() {
                return Set.of(dependency);
            }

            @Override
            public boolean supports(AggregationContext context) {
                return context.accountGroupResponse().path(requiredFlag).asBoolean(false);
            }

            @Override
            public Mono<JsonNode> fetch(AggregationContext context) {
                return Mono.just(objectMapper.createObjectNode());
            }

            @Override
            public void merge(ObjectNode root, JsonNode enrichmentResponse) {
                root.put(targetFlag, true);
            }
        };
    }

    protected AggregationEnrichment failingFetchEnrichment(String name) {
        return new AggregationEnrichment() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Mono<JsonNode> fetch(AggregationContext context) {
                return Mono.error(new IllegalStateException("enrichment down"));
            }

            @Override
            public void merge(ObjectNode root, JsonNode enrichmentResponse) {
                root.put("failedEnrichmentMerged", true);
            }
        };
    }

    protected AggregationEnrichment unsupportedEnrichment(String name) {
        return new AggregationEnrichment() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public boolean supports(AggregationContext context) {
                return false;
            }

            @Override
            public Mono<JsonNode> fetch(AggregationContext context) {
                return Mono.just(objectMapper.createObjectNode());
            }

            @Override
            public void merge(ObjectNode root, JsonNode enrichmentResponse) {
                root.put("unsupportedEnrichmentRan", true);
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
