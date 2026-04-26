package dev.abramenka.aggregation.enrichment.owners;

import static org.assertj.core.api.Assertions.assertThat;

import dev.abramenka.aggregation.model.AggregationContext;
import dev.abramenka.aggregation.model.AggregationPart;
import dev.abramenka.aggregation.model.AggregationPartResult;
import dev.abramenka.aggregation.model.ClientRequestContext;
import dev.abramenka.aggregation.model.ForwardedHeaders;
import dev.abramenka.aggregation.model.PartOutcomeStatus;
import dev.abramenka.aggregation.model.PartSkipReason;
import dev.abramenka.aggregation.model.Projections;
import dev.abramenka.aggregation.workflow.WorkflowBindingMetrics;
import dev.abramenka.aggregation.workflow.WorkflowExecutor;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class OwnersEnrichmentWorkflowTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    private SimpleMeterRegistry meterRegistry;
    private AggregationPart owners;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        WorkflowExecutor executor = new WorkflowExecutor(new WorkflowBindingMetrics(meterRegistry));
        owners = new OwnersEnrichment(this::fetchOwners, executor);
    }

    // -------------------------------------------------------------------------
    // 1. Fallback source key path: basicDetails.owners[*].id
    // -------------------------------------------------------------------------

    @Test
    void extractsKeysFromOwnersId() {
        AggregationContext ctx = contextFor("""
                {"data": [{"basicDetails": {"owners": [{"id": "o1"}, {"id": "o2"}]}}]}
                """);
        JsonNode response = json("""
                {"data": [
                  {"individual": {"number": "o1"}, "name": "Alice"},
                  {"individual": {"number": "o2"}, "name": "Bob"}
                ]}
                """);
        owners = withResponse(response);

        StepVerifier.create(owners.execute(ctx))
                .assertNext(result -> {
                    assertThat(result).isInstanceOf(AggregationPartResult.JsonPatch.class);
                    // both owners written to owners1
                    assertThat(((AggregationPartResult.JsonPatch) result)
                                    .patch()
                                    .operations())
                            .hasSize(3);
                    // create array + 2 appends
                })
                .verifyComplete();
    }

    // -------------------------------------------------------------------------
    // 2. Fallback source key path: basicDetails.owners[*].number
    // -------------------------------------------------------------------------

    @Test
    void extractsKeysFromOwnersNumberWhenIdAbsent() {
        AggregationContext ctx = contextFor("""
                {"data": [{"basicDetails": {"owners": [{"number": "n1"}]}}]}
                """);
        JsonNode response = json("""
                {"data": [{"individual": {"number": "n1"}, "name": "Carol"}]}
                """);
        owners = withResponse(response);

        StepVerifier.create(owners.execute(ctx))
                .assertNext(result -> assertThat(result).isInstanceOf(AggregationPartResult.JsonPatch.class))
                .verifyComplete();
    }

    // -------------------------------------------------------------------------
    // 3. Response indexed by individual.number (primary)
    // -------------------------------------------------------------------------

    @Test
    void indexesResponseByIndividualNumber() {
        AggregationContext ctx = contextFor("""
                {"data": [{"basicDetails": {"owners": [{"id": "o1"}]}}]}
                """);
        JsonNode response = json("""
                {"data": [{"individual": {"number": "o1"}, "name": "Dave"}]}
                """);
        owners = withResponse(response);

        StepVerifier.create(owners.execute(ctx))
                .assertNext(result -> {
                    assertThat(result).isInstanceOf(AggregationPartResult.JsonPatch.class);
                    assertThat(((AggregationPartResult.JsonPatch) result)
                                    .patch()
                                    .operations())
                            .isNotEmpty();
                })
                .verifyComplete();
    }

    // -------------------------------------------------------------------------
    // 4. Response indexed by id when individual.number is absent
    // -------------------------------------------------------------------------

    @Test
    void indexesResponseByIdWhenIndividualNumberAbsent() {
        AggregationContext ctx = contextFor("""
                {"data": [{"basicDetails": {"owners": [{"id": "o1"}]}}]}
                """);
        JsonNode response = json("""
                {"data": [{"id": "o1", "name": "Eve"}]}
                """);
        owners = withResponse(response);

        StepVerifier.create(owners.execute(ctx))
                .assertNext(result -> {
                    assertThat(result).isInstanceOf(AggregationPartResult.JsonPatch.class);
                    assertThat(((AggregationPartResult.JsonPatch) result)
                                    .patch()
                                    .operations())
                            .isNotEmpty();
                })
                .verifyComplete();
    }

    // -------------------------------------------------------------------------
    // 5. Matched owners written to owners1
    // -------------------------------------------------------------------------

    @Test
    void writesMatchedOwnersToOwners1() {
        ObjectNode root = parseObject("""
                {"data": [{"basicDetails": {"owners": [{"id": "o1"}]}}]}
                """);
        AggregationContext ctx = contextFor(root);
        JsonNode response = json("""
                {"data": [{"individual": {"number": "o1"}, "name": "Frank"}]}
                """);
        owners = withResponse(response);

        StepVerifier.create(owners.execute(ctx))
                .assertNext(result -> {
                    assertThat(result).isInstanceOf(AggregationPartResult.JsonPatch.class);
                    // patch must reference /data/0/owners1
                    boolean hasOwners1Write = ((AggregationPartResult.JsonPatch) result)
                            .patch().operations().stream()
                                    .anyMatch(op -> op.path().contains("owners1"));
                    assertThat(hasOwners1Write).isTrue();
                })
                .verifyComplete();
    }

    // -------------------------------------------------------------------------
    // 6. owners1 array auto-created when absent
    // -------------------------------------------------------------------------

    @Test
    void autoCreatesOwners1ArrayWhenAbsent() {
        AggregationContext ctx = contextFor("""
                {"data": [{"basicDetails": {"owners": [{"id": "o1"}]}}]}
                """);
        // data item has no owners1 field yet
        JsonNode response = json("""
                {"data": [{"individual": {"number": "o1"}, "name": "Grace"}]}
                """);
        owners = withResponse(response);

        StepVerifier.create(owners.execute(ctx))
                .assertNext(result -> {
                    assertThat(result).isInstanceOf(AggregationPartResult.JsonPatch.class);
                    var ops = ((AggregationPartResult.JsonPatch) result).patch().operations();
                    // first op: add /data/0/owners1 [] (create)
                    // second op: add /data/0/owners1/- (append)
                    assertThat(ops).hasSize(2);
                    assertThat(ops.get(0).path()).isEqualTo("/data/0/owners1");
                    assertThat(ops.get(1).path()).isEqualTo("/data/0/owners1/-");
                })
                .verifyComplete();
    }

    // -------------------------------------------------------------------------
    // 7a. No keys → SKIPPED / NO_KEYS_IN_MAIN
    // -------------------------------------------------------------------------

    @Test
    void noKeys_returnsSkipped() {
        AggregationContext ctx = contextFor("""
                {"data": [{"basicDetails": {"owners": []}}]}
                """);

        StepVerifier.create(owners.execute(ctx))
                .assertNext(result -> {
                    assertThat(result).isInstanceOf(AggregationPartResult.NoOp.class);
                    var noOp = (AggregationPartResult.NoOp) result;
                    assertThat(noOp.status()).isEqualTo(PartOutcomeStatus.SKIPPED);
                    assertThat(noOp.reason()).isEqualTo(PartSkipReason.NO_KEYS_IN_MAIN);
                })
                .verifyComplete();
    }

    // -------------------------------------------------------------------------
    // 7b. Empty downstream → EMPTY / DOWNSTREAM_EMPTY
    // -------------------------------------------------------------------------

    @Test
    void emptyDownstream_returnsEmpty() {
        AggregationContext ctx = contextFor("""
                {"data": [{"basicDetails": {"owners": [{"id": "o1"}]}}]}
                """);

        // Downstream returns Mono.empty() — simulates no-body response
        WorkflowExecutor executor = new WorkflowExecutor(new WorkflowBindingMetrics(meterRegistry));
        owners = new OwnersEnrichment((req, fields, clientCtx) -> Mono.empty(), executor);

        StepVerifier.create(owners.execute(ctx))
                .assertNext(result -> {
                    assertThat(result).isInstanceOf(AggregationPartResult.NoOp.class);
                    var noOp = (AggregationPartResult.NoOp) result;
                    assertThat(noOp.status()).isEqualTo(PartOutcomeStatus.EMPTY);
                    assertThat(noOp.reason()).isEqualTo(PartSkipReason.DOWNSTREAM_EMPTY);
                })
                .verifyComplete();
    }

    // -------------------------------------------------------------------------
    // 8. Binding metric emitted with binding=owners
    // -------------------------------------------------------------------------

    @Test
    void emitsBindingMetricWithOwnersTag() {
        AggregationContext ctx = contextFor("""
                {"data": [{"basicDetails": {"owners": [{"id": "o1"}]}}]}
                """);
        JsonNode response = json("""
                {"data": [{"individual": {"number": "o1"}, "name": "Hank"}]}
                """);
        owners = withResponse(response);

        StepVerifier.create(owners.execute(ctx)).expectNextCount(1).verifyComplete();

        assertThat(meterRegistry
                        .get("aggregation.binding.requests")
                        .tag("part", "owners")
                        .tag("binding", "owners")
                        .tag("outcome", "success")
                        .counter()
                        .count())
                .isEqualTo(1.0);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private AggregationContext contextFor(String json) {
        return contextFor(parseObject(json));
    }

    private AggregationContext contextFor(ObjectNode root) {
        ClientRequestContext clientCtx =
                new ClientRequestContext(ForwardedHeaders.builder().build(), null, Projections.empty());
        return new AggregationContext(root, clientCtx);
    }

    /** Creates an {@link OwnersEnrichment} wired to return {@code response} from the downstream call. */
    private AggregationPart withResponse(JsonNode response) {
        WorkflowExecutor executor = new WorkflowExecutor(new WorkflowBindingMetrics(meterRegistry));
        return new OwnersEnrichment((request, fields, clientCtx) -> Mono.just(response), executor);
    }

    /** Default downstream fetch used by {@link #setUp()} — returns empty to satisfy construction. */
    private Mono<JsonNode> fetchOwners(
            ObjectNode request,
            String fields,
            dev.abramenka.aggregation.model.ClientRequestContext clientRequestContext) {
        return Mono.empty();
    }

    private ObjectNode parseObject(String json) {
        try {
            return (ObjectNode) mapper.readTree(json);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private JsonNode json(String raw) {
        try {
            return mapper.readTree(raw);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
