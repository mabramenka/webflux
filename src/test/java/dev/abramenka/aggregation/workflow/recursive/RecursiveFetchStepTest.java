package dev.abramenka.aggregation.workflow.recursive;

import static org.assertj.core.api.Assertions.assertThat;

import dev.abramenka.aggregation.model.AggregationContext;
import dev.abramenka.aggregation.model.ClientRequestContext;
import dev.abramenka.aggregation.model.ForwardedHeaders;
import dev.abramenka.aggregation.model.Projections;
import dev.abramenka.aggregation.workflow.StepResult;
import dev.abramenka.aggregation.workflow.WorkflowContext;
import dev.abramenka.aggregation.workflow.binding.KeySource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

class RecursiveFetchStepTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void execute_readsSeedGroupsFromRootSnapshot() {
        WorkflowContext context = context("""
                {"data":[{"id":"root-1"}]}
                """);
        context.currentRoot()
                .set(
                        "data",
                        mapper.createArrayNode().add(mapper.createObjectNode().put("id", "current-1")));
        List<List<String>> fetchCalls = new ArrayList<>();

        RecursiveFetchStep step = step(
                KeySource.ROOT_SNAPSHOT,
                this::groupsFromDataIds,
                fetcher(fetchCalls),
                this::noChildren,
                this::isIndividual,
                "result");

        StepVerifier.create(step.execute(context))
                .assertNext(result -> assertThat(storedValue(result)
                                .path("groups")
                                .path(0)
                                .path("resolvedNodes")
                                .path(0)
                                .path("number")
                                .asString())
                        .isEqualTo("root-1"))
                .verifyComplete();

        assertThat(fetchCalls).containsExactly(List.of("root-1"));
    }

    @Test
    void execute_readsSeedGroupsFromCurrentRoot() {
        WorkflowContext context = context("""
                {"data":[{"id":"root-1"}]}
                """);
        context.currentRoot()
                .set(
                        "data",
                        mapper.createArrayNode().add(mapper.createObjectNode().put("id", "current-1")));
        List<List<String>> fetchCalls = new ArrayList<>();

        RecursiveFetchStep step = step(
                KeySource.CURRENT_ROOT,
                this::groupsFromDataIds,
                fetcher(fetchCalls),
                this::noChildren,
                this::isIndividual,
                "result");

        StepVerifier.create(step.execute(context))
                .assertNext(result -> assertThat(storedValue(result)
                                .path("groups")
                                .path(0)
                                .path("resolvedNodes")
                                .path(0)
                                .path("number")
                                .asString())
                        .isEqualTo("current-1"))
                .verifyComplete();

        assertThat(fetchCalls).containsExactly(List.of("current-1"));
    }

    @Test
    void execute_delegatesTraversalToEngine() {
        WorkflowContext context = context("""
                {"data":[{"id":"a"}]}
                """);
        AtomicInteger fetchCount = new AtomicInteger();
        RecursiveTraversalEngine.BatchFetcher fetcher = keys -> {
            fetchCount.incrementAndGet();
            return Mono.just(responseForKeys(keys));
        };

        RecursiveFetchStep step = step(
                KeySource.ROOT_SNAPSHOT,
                this::groupsFromDataIds,
                fetcher,
                this::noChildren,
                this::isIndividual,
                "result");

        StepVerifier.create(step.execute(context)).expectNextCount(1).verifyComplete();

        assertThat(fetchCount.get()).isEqualTo(1);
    }

    @Test
    void execute_storesTraversalJsonWithGroups_underConfiguredStoreAs_withoutPatchOrVariableWrites() {
        WorkflowContext context = context("""
                {"data":[{"id":"a"}]}
                """);
        RecursiveFetchStep step = step(
                KeySource.ROOT_SNAPSHOT,
                this::groupsFromDataIds,
                fetcher(new ArrayList<>()),
                this::noChildren,
                this::isIndividual,
                "traversal");

        StepVerifier.create(step.execute(context))
                .assertNext(result -> {
                    assertThat(result).isInstanceOf(StepResult.Applied.class);
                    StepResult.Applied applied = (StepResult.Applied) result;
                    assertThat(applied.patch()).isNull();
                    assertThat(applied.storeAs()).isEqualTo("traversal");
                    assertThat(applied.storedValue()).isNotNull();
                    assertThat(storedValue(result).path("groups").isArray()).isTrue();
                })
                .verifyComplete();

        assertThat(context.variables().contains("traversal")).isFalse();
    }

    @Test
    void execute_preservesPerGroupMetadataAndGroupOrderInStoredJson() {
        WorkflowContext context = context("""
                {"data":[{"id":"b"},{"id":"a"}]}
                """);
        RecursiveFetchStep step = step(
                KeySource.ROOT_SNAPSHOT,
                this::groupsWithMetadataPerOwner,
                fetcher(new ArrayList<>()),
                this::noChildren,
                this::isIndividual,
                "result");

        StepVerifier.create(step.execute(context))
                .assertNext(result -> {
                    JsonNode groups = storedValue(result).path("groups");
                    assertThat(groups.path(0)
                                    .path("targetMetadata")
                                    .path("ownerIndex")
                                    .asInt())
                            .isEqualTo(0);
                    assertThat(groups.path(1)
                                    .path("targetMetadata")
                                    .path("ownerIndex")
                                    .asInt())
                            .isEqualTo(1);
                    assertThat(groups.path(0)
                                    .path("resolvedNodes")
                                    .path(0)
                                    .path("number")
                                    .asString())
                            .isEqualTo("b");
                    assertThat(groups.path(1)
                                    .path("resolvedNodes")
                                    .path(0)
                                    .path("number")
                                    .asString())
                            .isEqualTo("a");
                })
                .verifyComplete();
    }

    @Test
    void execute_emptySeedGroups_storesEmptyTraversalResultAndSkipsFetch() {
        WorkflowContext context = context("""
                {"data":[]}
                """);
        AtomicInteger fetchCount = new AtomicInteger();
        RecursiveTraversalEngine.BatchFetcher fetcher = keys -> {
            fetchCount.incrementAndGet();
            return Mono.just(responseForKeys(keys));
        };
        RecursiveFetchStep step = step(
                KeySource.ROOT_SNAPSHOT, source -> List.of(), fetcher, this::noChildren, this::isIndividual, "result");

        StepVerifier.create(step.execute(context))
                .assertNext(
                        result -> assertThat(storedValue(result).path("groups")).isEmpty())
                .verifyComplete();

        assertThat(fetchCount.get()).isZero();
    }

    @Test
    void execute_doesNotMutateRootSnapshotOrCurrentRoot() {
        WorkflowContext context = context("""
                {"data":[{"id":"a"}]}
                """);
        String beforeRootSnapshot = context.rootSnapshot().toString();
        String beforeCurrentRoot = context.currentRoot().toString();
        RecursiveFetchStep step = step(
                KeySource.ROOT_SNAPSHOT,
                this::groupsFromDataIds,
                fetcher(new ArrayList<>()),
                this::noChildren,
                this::isIndividual,
                "result");

        StepVerifier.create(step.execute(context)).expectNextCount(1).verifyComplete();

        assertThat(context.rootSnapshot()).hasToString(beforeRootSnapshot);
        assertThat(context.currentRoot()).hasToString(beforeCurrentRoot);
    }

    private RecursiveFetchStep step(
            KeySource source,
            RecursiveFetchStep.TraversalSeedGroupExtractor seedGroupExtractor,
            RecursiveTraversalEngine.BatchFetcher fetcher,
            RecursiveTraversalEngine.ChildKeyExtractor childKeyExtractor,
            java.util.function.Predicate<JsonNode> isTerminalNode,
            String storeAs) {
        TraversalPolicy policy = new TraversalPolicy(6, CyclePolicy.SKIP_VISITED, true);
        return new RecursiveFetchStep(
                "recursiveFetch",
                storeAs,
                source,
                seedGroupExtractor,
                policy,
                new RecursiveTraversalEngine(),
                fetcher,
                childKeyExtractor,
                isTerminalNode);
    }

    private RecursiveTraversalEngine.BatchFetcher fetcher(List<List<String>> fetchCalls) {
        return keys -> {
            fetchCalls.add(new ArrayList<>(keys));
            return Mono.just(responseForKeys(keys));
        };
    }

    private Map<String, JsonNode> responseForKeys(Set<String> keys) {
        Map<String, JsonNode> out = new LinkedHashMap<>();
        for (String key : keys) {
            out.put(key, individual(key));
        }
        return out;
    }

    private List<TraversalSeedGroup> groupsFromDataIds(JsonNode source) {
        JsonNode data = source.path("data");
        if (!data.isArray()) {
            return List.of();
        }
        List<String> keys = new ArrayList<>();
        data.values().forEach(item -> keys.add(item.path("id").asString("")));
        return List.of(new TraversalSeedGroup(null, keys));
    }

    private List<TraversalSeedGroup> groupsWithMetadataPerOwner(JsonNode source) {
        JsonNode data = source.path("data");
        if (!data.isArray()) {
            return List.of();
        }
        List<TraversalSeedGroup> out = new ArrayList<>();
        for (int i = 0; i < data.size(); i++) {
            JsonNode item = data.path(i);
            ObjectNode metadata = JsonNodeFactory.instance.objectNode();
            metadata.put("ownerIndex", i);
            out.add(new TraversalSeedGroup(metadata, List.of(item.path("id").asString(""))));
        }
        return out;
    }

    private Iterable<String> noChildren(JsonNode node) {
        return List.of();
    }

    private boolean isIndividual(JsonNode node) {
        return "individual".equals(node.path("kind").asString());
    }

    private JsonNode individual(String number) {
        ObjectNode owner = mapper.createObjectNode();
        owner.put("kind", "individual");
        owner.put("number", number);
        return owner;
    }

    private JsonNode storedValue(StepResult result) {
        StepResult.Applied applied = (StepResult.Applied) result;
        return Objects.requireNonNull(applied.storedValue(), "storedValue");
    }

    private WorkflowContext context(String rootJson) {
        ObjectNode root;
        try {
            root = (ObjectNode) mapper.readTree(rootJson);
        } catch (Exception ex) {
            throw new IllegalArgumentException(ex);
        }
        AggregationContext aggregationContext = new AggregationContext(
                root, new ClientRequestContext(ForwardedHeaders.builder().build(), null, Projections.empty()));
        return new WorkflowContext(aggregationContext, root);
    }
}
