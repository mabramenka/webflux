package dev.abramenka.aggregation.workflow.recursive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Objects;
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
        TraversalGroupResult first =
                new TraversalGroupResult(groupMetadata("g-1"), List.of(new TraversalNode("A", owner("A"), 1)));
        TraversalGroupResult second =
                new TraversalGroupResult(groupMetadata("g-2"), List.of(new TraversalNode("B", owner("B"), 2)));
        TraversalResult result = new TraversalResult(List.of(first, second));

        assertThat(result.groups()).extracting(this::groupId).containsExactly("g-1", "g-2");
    }

    @Test
    void traversalResult_representsGenericTargetMetadataForLaterReducerUse() {
        TraversalGroupResult group =
                new TraversalGroupResult(groupMetadata("g-1"), List.of(new TraversalNode("P-1", owner("P-1"), 2)));
        TraversalResult result = new TraversalResult(List.of(group));

        assertThat(result.toJsonNode(JsonNodeFactory.instance)
                        .path("groups")
                        .path(0)
                        .path("targetMetadata")
                        .path("dataIndex")
                        .asInt())
                .isEqualTo(3);
        assertThat(result.toJsonNode(JsonNodeFactory.instance)
                        .path("groups")
                        .path(0)
                        .path("targetMetadata")
                        .path("ownerIndex")
                        .asInt())
                .isEqualTo(1);
        assertThat(result.toJsonNode(JsonNodeFactory.instance)
                        .path("groups")
                        .path(0)
                        .path("resolvedNodes")
                        .path(0)
                        .path("number")
                        .asString())
                .isEqualTo("P-1");
    }

    private ObjectNode groupMetadata(String groupId) {
        ObjectNode metadata = JsonNodeFactory.instance.objectNode();
        metadata.put("groupId", groupId);
        metadata.put("dataIndex", 3);
        metadata.put("ownerIndex", 1);
        return metadata;
    }

    private String groupId(TraversalGroupResult group) {
        return Objects.requireNonNull(group.targetMetadata(), "targetMetadata")
                .path("groupId")
                .asString();
    }

    private ObjectNode owner(String number) {
        ObjectNode owner = JsonNodeFactory.instance.objectNode();
        owner.putObject("individual").put("number", number);
        return owner;
    }
}
