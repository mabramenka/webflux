package dev.abramenka.aggregation.workflow.recursive;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

class RecursiveTraversalEngineTest {

    private final RecursiveTraversalEngine engine = new RecursiveTraversalEngine();

    @Test
    void traverse_fetchesLevelByLevelInDeterministicOrder() {
        Map<String, JsonNode> nodesByKey = new LinkedHashMap<>();
        nodesByKey.put("A", entity("A", "B", "C"));
        nodesByKey.put("B", entity("B", "D"));
        nodesByKey.put("C", individual("C"));
        nodesByKey.put("D", individual("D"));
        List<List<String>> calls = new ArrayList<>();

        StepVerifier.create(engine.traverse(
                        List.of(group(List.of("A"), metadata("g-1"))),
                        policy(6),
                        fetcher(nodesByKey, calls),
                        this::childKeys,
                        this::isIndividual))
                .assertNext(result -> {
                    assertThat(singleGroup(result).resolvedNodes())
                            .extracting(TraversalNode::number)
                            .containsExactly("C", "D");
                    assertThat(singleGroup(result).resolvedNodes())
                            .extracting(TraversalNode::depth)
                            .containsExactly(2, 3);
                })
                .verifyComplete();

        assertThat(calls).containsExactly(List.of("A"), List.of("B", "C"), List.of("D"));
    }

    @Test
    void traverse_allowsDepthOneThroughMaxDepth() {
        Map<String, JsonNode> nodesByKey = new LinkedHashMap<>();
        nodesByKey.put("A", entity("A", "B"));
        nodesByKey.put("B", entity("B", "C"));
        nodesByKey.put("C", individual("C"));

        StepVerifier.create(engine.traverse(
                        List.of(group(List.of("A"), metadata("g-1"))),
                        policy(3),
                        fetcher(nodesByKey, new ArrayList<>()),
                        this::childKeys,
                        this::isIndividual))
                .assertNext(result -> assertThat(singleGroup(result).resolvedNodes())
                        .singleElement()
                        .satisfies(node -> {
                            assertThat(node.number()).isEqualTo("C");
                            assertThat(node.depth()).isEqualTo(3);
                        }))
                .verifyComplete();
    }

    @Test
    void traverse_failsOnlyWhenAttemptingDepthBeyondMaxDepth() {
        Map<String, JsonNode> nodesByKey = new LinkedHashMap<>();
        nodesByKey.put("A", entity("A", "B"));
        nodesByKey.put("B", entity("B", "C"));
        nodesByKey.put("C", individual("C"));
        List<List<String>> calls = new ArrayList<>();

        StepVerifier.create(engine.traverse(
                        List.of(group(List.of("A"), metadata("g-1"))),
                        policy(2),
                        fetcher(nodesByKey, calls),
                        this::childKeys,
                        this::isIndividual))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(RecursiveTraversalEngine.TraversalException.class);
                    assertThat(((RecursiveTraversalEngine.TraversalException) error).reason())
                            .isEqualTo(RecursiveTraversalEngine.TraversalException.Reason.DEPTH_EXCEEDED);
                })
                .verify();

        assertThat(calls).containsExactly(List.of("A"), List.of("B"));
    }

    @Test
    void traverse_skipVisitedPreventsInfiniteCycle() {
        Map<String, JsonNode> nodesByKey = new LinkedHashMap<>();
        nodesByKey.put("A", entity("A", "B"));
        nodesByKey.put("B", entity("B", "A"));
        List<List<String>> calls = new ArrayList<>();

        StepVerifier.create(engine.traverse(
                        List.of(group(List.of("A"), metadata("g-1"))),
                        policy(6),
                        fetcher(nodesByKey, calls),
                        this::childKeys,
                        this::isIndividual))
                .assertNext(result ->
                        assertThat(singleGroup(result).resolvedNodes()).isEmpty())
                .verifyComplete();

        assertThat(calls).containsExactly(List.of("A"), List.of("B"));
    }

    @Test
    void traverse_doesNotFetchAlreadyVisitedKeysAgain() {
        Map<String, JsonNode> nodesByKey = new LinkedHashMap<>();
        nodesByKey.put("A", entity("A", "B", "C"));
        nodesByKey.put("B", individual("B"));
        nodesByKey.put("C", entity("C", "B"));
        List<List<String>> calls = new ArrayList<>();

        StepVerifier.create(engine.traverse(
                        List.of(group(List.of("A"), metadata("g-1"))),
                        policy(6),
                        fetcher(nodesByKey, calls),
                        this::childKeys,
                        this::isIndividual))
                .assertNext(result -> assertThat(singleGroup(result).resolvedNodes())
                        .extracting(TraversalNode::number)
                        .containsExactly("B"))
                .verifyComplete();

        assertThat(calls).containsExactly(List.of("A"), List.of("B", "C"));
    }

    @Test
    void traverse_preservesFirstSeenOrderAcrossDuplicateSeeds() {
        Map<String, JsonNode> nodesByKey = new LinkedHashMap<>();
        nodesByKey.put("X", individual("X"));
        nodesByKey.put("A", individual("A"));
        nodesByKey.put("B", individual("B"));
        List<List<String>> calls = new ArrayList<>();

        StepVerifier.create(engine.traverse(
                        List.of(group(List.of("X", "A", "X", "B"), metadata("g-1"))),
                        policy(2),
                        fetcher(nodesByKey, calls),
                        this::childKeys,
                        this::isIndividual))
                .assertNext(result -> assertThat(singleGroup(result).resolvedNodes())
                        .extracting(TraversalNode::number)
                        .containsExactly("X", "A", "B"))
                .verifyComplete();

        assertThat(calls).containsExactly(List.of("X", "A", "B"));
    }

    @Test
    void traverse_failsWhenResponseMissesRequestedKey() {
        Map<String, JsonNode> nodesByKey = new LinkedHashMap<>();
        nodesByKey.put("A", individual("A"));
        List<List<String>> calls = new ArrayList<>();

        StepVerifier.create(engine.traverse(
                        List.of(group(List.of("A", "B"), metadata("g-1"))),
                        policy(2),
                        fetcher(nodesByKey, calls),
                        this::childKeys,
                        this::isIndividual))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(RecursiveTraversalEngine.TraversalException.class);
                    assertThat(((RecursiveTraversalEngine.TraversalException) error).reason())
                            .isEqualTo(RecursiveTraversalEngine.TraversalException.Reason.CONTRACT_VIOLATION);
                })
                .verify();
    }

    @Test
    void traverse_returnsEmptyResultWhenInitialFrontierIsEmpty() {
        AtomicInteger callCount = new AtomicInteger();
        RecursiveTraversalEngine.BatchFetcher fetcher = keys -> {
            callCount.incrementAndGet();
            return Mono.just(Map.of());
        };

        StepVerifier.create(engine.traverse(
                        List.of(group(List.of(), metadata("g-1"))),
                        policy(3),
                        fetcher,
                        this::childKeys,
                        this::isIndividual))
                .assertNext(result -> {
                    assertThat(result.groups()).hasSize(1);
                    assertThat(singleGroup(result).resolvedNodes()).isEmpty();
                })
                .verifyComplete();

        assertThat(callCount.get()).isZero();
    }

    @Test
    void traverse_preservesPerGroupTargetMetadata() {
        StepVerifier.create(engine.traverse(
                        List.of(group(List.of(), metadata("g-1")), group(List.of(), metadata("g-2"))),
                        policy(2),
                        keys -> Mono.just(Map.of()),
                        this::childKeys,
                        this::isIndividual))
                .assertNext(result -> {
                    assertThat(result.groups()).extracting(this::groupId).containsExactly("g-1", "g-2");
                })
                .verifyComplete();
    }

    @Test
    void traverse_sameSeedInDifferentGroups_isResolvedIndependentlyPerGroup() {
        Map<String, JsonNode> nodesByKey = new LinkedHashMap<>();
        nodesByKey.put("A", individual("A"));
        List<List<String>> calls = new ArrayList<>();

        StepVerifier.create(engine.traverse(
                        List.of(group(List.of("A"), metadata("g-1")), group(List.of("A"), metadata("g-2"))),
                        policy(3),
                        fetcher(nodesByKey, calls),
                        this::childKeys,
                        this::isIndividual))
                .assertNext(result -> {
                    assertThat(result.groups()).hasSize(2);
                    assertThat(result.groups().get(0).resolvedNodes())
                            .extracting(TraversalNode::number)
                            .containsExactly("A");
                    assertThat(result.groups().get(1).resolvedNodes())
                            .extracting(TraversalNode::number)
                            .containsExactly("A");
                })
                .verifyComplete();

        assertThat(calls).containsExactly(List.of("A"), List.of("A"));
    }

    @Test
    void traverse_preservesGroupOrder() {
        Map<String, JsonNode> nodesByKey = new LinkedHashMap<>();
        nodesByKey.put("A", individual("A"));
        nodesByKey.put("B", individual("B"));

        StepVerifier.create(engine.traverse(
                        List.of(group(List.of("B"), metadata("second")), group(List.of("A"), metadata("first"))),
                        policy(2),
                        fetcher(nodesByKey, new ArrayList<>()),
                        this::childKeys,
                        this::isIndividual))
                .assertNext(result ->
                        assertThat(result.groups()).extracting(this::groupId).containsExactly("second", "first"))
                .verifyComplete();
    }

    @Test
    void engineApi_doesNotDependOnRootOrWorkflowOrPatchTypes() {
        List<String> apiTypeNames = Arrays.stream(RecursiveTraversalEngine.class.getDeclaredMethods())
                .filter(method -> method.getDeclaringClass().equals(RecursiveTraversalEngine.class))
                .flatMap(method ->
                        Stream.concat(Stream.of(method.getReturnType()), Arrays.stream(method.getParameterTypes())))
                .map(Class::getName)
                .toList();

        assertThat(apiTypeNames)
                .noneMatch(type -> type.contains("AggregationContext")
                        || type.contains("WorkflowContext")
                        || type.contains("WorkflowExecutor")
                        || type.contains("WorkflowVariableStore")
                        || type.contains("JsonPatchDocument"));
    }

    private TraversalPolicy policy(int maxDepth) {
        return new TraversalPolicy(maxDepth, CyclePolicy.SKIP_VISITED, true);
    }

    private TraversalSeedGroup group(List<String> initialKeys, @Nullable JsonNode metadata) {
        return new TraversalSeedGroup(metadata, initialKeys);
    }

    private TraversalGroupResult singleGroup(TraversalResult result) {
        assertThat(result.groups()).hasSize(1);
        return result.groups().getFirst();
    }

    private ObjectNode metadata(String groupId) {
        ObjectNode metadata = JsonNodeFactory.instance.objectNode();
        metadata.put("groupId", groupId);
        return metadata;
    }

    private String groupId(TraversalGroupResult group) {
        return Objects.requireNonNull(group.targetMetadata(), "targetMetadata")
                .path("groupId")
                .asString();
    }

    private RecursiveTraversalEngine.BatchFetcher fetcher(Map<String, JsonNode> nodesByKey, List<List<String>> calls) {
        return keys -> {
            calls.add(new ArrayList<>(keys));
            Map<String, JsonNode> response = new LinkedHashMap<>();
            for (String key : keys) {
                JsonNode node = nodesByKey.get(key);
                if (node != null) {
                    response.put(key, node);
                }
            }
            return Mono.just(response);
        };
    }

    private Iterable<String> childKeys(JsonNode node) {
        JsonNode children = node.path("children");
        if (!children.isArray()) {
            return List.of();
        }
        List<String> keys = new ArrayList<>();
        children.values().forEach(child -> keys.add(child.asString()));
        return keys;
    }

    private boolean isIndividual(JsonNode node) {
        return "individual".equals(node.path("kind").asString());
    }

    private JsonNode individual(String number) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("kind", "individual");
        node.put("number", number);
        return node;
    }

    private JsonNode entity(String number, String... children) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("kind", "entity");
        node.put("number", number);
        ArrayNode childArray = node.putArray("children");
        for (String child : children) {
            childArray.add(child);
        }
        return node;
    }
}
