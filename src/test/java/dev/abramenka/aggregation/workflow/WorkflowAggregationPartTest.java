package dev.abramenka.aggregation.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.abramenka.aggregation.enrichment.account.AccountEnrichmentTestFactory;
import dev.abramenka.aggregation.model.AggregationContext;
import dev.abramenka.aggregation.model.AggregationPartResult;
import dev.abramenka.aggregation.model.ClientRequestContext;
import dev.abramenka.aggregation.model.ForwardedHeaders;
import dev.abramenka.aggregation.model.PartCriticality;
import dev.abramenka.aggregation.model.Projections;
import dev.abramenka.aggregation.patch.JsonPatchBuilder;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class WorkflowAggregationPartTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void exposesWorkflowMetadata() {
        WorkflowAggregationPart part = new TestPart(
                new AggregationWorkflow("test", Set.of("dep"), PartCriticality.OPTIONAL, List.of(applyStep("s1"))),
                AccountEnrichmentTestFactory.noopWorkflowExecutor());

        assertThat(part.name()).isEqualTo("test");
        assertThat(part.dependencies()).containsExactly("dep");
        assertThat(part.criticality()).isEqualTo(PartCriticality.OPTIONAL);
    }

    @Test
    void executeRoundTripsThroughExecutorAndProducesJsonPatchResult() {
        WorkflowAggregationPart part = new TestPart(
                new AggregationWorkflow("test", Set.of(), PartCriticality.REQUIRED, List.of(applyStep("s1"))),
                AccountEnrichmentTestFactory.noopWorkflowExecutor());

        StepVerifier.create(part.execute(context()))
                .assertNext(result -> {
                    assertThat(result).isInstanceOf(AggregationPartResult.JsonPatch.class);
                    assertThat(result.partName()).isEqualTo("test");
                })
                .verifyComplete();
    }

    @Test
    void invalidWorkflowFailsAtConstruction() {
        AggregationWorkflow invalid = new AggregationWorkflow(
                "test", Set.of(), PartCriticality.REQUIRED, List.of(applyStep("dup"), applyStep("dup")));

        assertThatThrownBy(() -> new TestPart(invalid, AccountEnrichmentTestFactory.noopWorkflowExecutor()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate step name");
    }

    private AggregationContext context() {
        ObjectNode root = mapper.createObjectNode();
        ClientRequestContext clientCtx =
                new ClientRequestContext(ForwardedHeaders.builder().build(), null, Projections.empty());
        return new AggregationContext(root, clientCtx);
    }

    private WorkflowStep applyStep(String name) {
        return new WorkflowStep() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Mono<StepResult> execute(WorkflowContext context) {
                return Mono.just(StepResult.applied(JsonPatchBuilder.create().build()));
            }
        };
    }

    private static final class TestPart extends WorkflowAggregationPart {
        TestPart(AggregationWorkflow workflow, WorkflowExecutor executor) {
            super(workflow, executor);
        }
    }
}
