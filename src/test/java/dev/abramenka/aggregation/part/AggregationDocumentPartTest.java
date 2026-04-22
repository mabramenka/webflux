package dev.abramenka.aggregation.part;

import static org.assertj.core.api.Assertions.assertThat;

import dev.abramenka.aggregation.model.AggregationContext;
import dev.abramenka.aggregation.model.AggregationPartResult;
import dev.abramenka.aggregation.model.AggregationPartSelection;
import dev.abramenka.aggregation.model.ClientRequestContext;
import dev.abramenka.aggregation.model.ForwardedHeaders;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class AggregationDocumentPartTest {

    private final JsonMapper objectMapper = JsonMapper.builder().build();

    @Test
    void execute_returnsPatchFromMutatedSnapshotWithoutChangingInputRoot() {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("customerId", "cust-1");
        AggregationDocumentPart part = new AggregationDocumentPart() {
            @Override
            public String name() {
                return "audit";
            }

            @Override
            public Mono<Void> apply(ObjectNode workingRoot, AggregationContext context) {
                return Mono.fromRunnable(() -> workingRoot.put("audited", true));
            }
        };

        StepVerifier.create(part.execute(root, context(root)))
                .assertNext(result -> {
                    AggregationPartResult.MergePatch patch = (AggregationPartResult.MergePatch) result;

                    assertThat(root.has("audited")).isFalse();
                    assertThat(patch.partName()).isEqualTo("audit");
                    assertThat(patch.base().path("customerId").asString()).isEqualTo("cust-1");
                    assertThat(patch.base().has("audited")).isFalse();
                    assertThat(patch.replacement().path("customerId").asString())
                            .isEqualTo("cust-1");
                    assertThat(patch.replacement().path("audited").asBoolean()).isTrue();
                })
                .verifyComplete();
    }

    private AggregationContext context(ObjectNode root) {
        return new AggregationContext(
                root,
                new ClientRequestContext(ForwardedHeaders.builder().build(), null),
                AggregationPartSelection.from(null));
    }
}
