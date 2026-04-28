package dev.abramenka.aggregation.workflow.recursive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

class TraversalModelTest {

    @Test
    void traversalPolicy_rejectsNonPositiveMaxDepth() {
        assertThatThrownBy(() -> new TraversalPolicy(0, CyclePolicy.SKIP_VISITED, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("maxDepth must be greater than zero");
    }

    @Test
    void cyclePolicy_containsSkipVisited() {
        assertThat(CyclePolicy.values()).contains(CyclePolicy.SKIP_VISITED);
    }

    @Test
    void traversalResult_preservesResolvedNodeInsertionOrder() {
        TraversalNode first = new TraversalNode("A", owner("A"), 1);
        TraversalNode second = new TraversalNode("B", owner("B"), 2);
        TraversalResult result = new TraversalResult(List.of(first, second));

        assertThat(result.resolvedNodes()).extracting(TraversalNode::number).containsExactly("A", "B");
    }

    @Test
    void traversalResult_representsGenericTargetMetadataForLaterReducerUse() {
        ObjectNode targetMetadata = JsonNodeFactory.instance.objectNode();
        targetMetadata.put("dataIndex", 3);
        targetMetadata.put("ownerIndex", 1);
        TraversalResult result = new TraversalResult(List.of(), targetMetadata);

        assertThat(result.toJsonNode(JsonNodeFactory.instance)
                        .path("targetMetadata")
                        .path("dataIndex")
                        .asInt())
                .isEqualTo(3);
        assertThat(result.toJsonNode(JsonNodeFactory.instance)
                        .path("targetMetadata")
                        .path("ownerIndex")
                        .asInt())
                .isEqualTo(1);
    }

    private ObjectNode owner(String number) {
        ObjectNode owner = JsonNodeFactory.instance.objectNode();
        owner.putObject("individual").put("number", number);
        return owner;
    }
}
