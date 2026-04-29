package dev.abramenka.aggregation.workflow.recursive;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

/**
 * Reusable level-by-level recursive traversal engine.
 */
public final class RecursiveTraversalEngine {

    @FunctionalInterface
    public interface BatchFetcher {
        Mono<Map<String, JsonNode>> fetchBatch(Set<String> keys);
    }

    @FunctionalInterface
    public interface ChildKeyExtractor {
        Iterable<String> childKeys(JsonNode node);
    }

    public Mono<TraversalResult> traverse(
            List<TraversalSeedGroup> seedGroups,
            TraversalPolicy policy,
            BatchFetcher batchFetcher,
            ChildKeyExtractor childKeyExtractor,
            Predicate<JsonNode> isTerminalNode) {
        Objects.requireNonNull(seedGroups, "seedGroups");
        TraversalExecution execution = new TraversalExecution(policy, batchFetcher, childKeyExtractor, isTerminalNode);
        List<TraversalSeedGroup> groups = List.copyOf(seedGroups);
        return traverseGroups(groups, 0, new ArrayList<>(), execution).map(TraversalResult::new);
    }

    private Mono<List<TraversalGroupResult>> traverseGroups(
            List<TraversalSeedGroup> seedGroups,
            int index,
            List<TraversalGroupResult> out,
            TraversalExecution execution) {
        if (index == seedGroups.size()) {
            return Mono.just(out);
        }
        TraversalSeedGroup group = seedGroups.get(index);
        Set<String> frontier = normalizeKeys(group.initialKeys());
        return traverseLevel(frontier, new LinkedHashSet<>(), new LinkedHashMap<>(), 1, execution)
                .flatMap(terminalNodes -> {
                    out.add(new TraversalGroupResult(group.targetMetadata(), new ArrayList<>(terminalNodes.values())));
                    return traverseGroups(seedGroups, index + 1, out, execution);
                });
    }

    private Mono<Map<String, TraversalNode>> traverseLevel(
            Set<String> frontier,
            Set<String> visitedKeys,
            Map<String, TraversalNode> terminalNodes,
            int depth,
            TraversalExecution execution) {
        if (frontier.isEmpty()) {
            return Mono.just(terminalNodes);
        }
        TraversalPolicy policy = execution.policy();
        if (depth > policy.maxDepth()) {
            return Mono.error(TraversalException.depthExceeded(policy.maxDepth()));
        }
        LinkedHashSet<String> toResolve = new LinkedHashSet<>(frontier);
        if (Objects.requireNonNull(policy.cyclePolicy()) == CyclePolicy.SKIP_VISITED) {
            toResolve.removeAll(visitedKeys);
        }
        if (toResolve.isEmpty()) {
            return Mono.just(terminalNodes);
        }
        return execution.batchFetcher().fetchBatch(toResolve).flatMap(responseByKey -> {
            requireAllRequestedKeys(toResolve, responseByKey, policy.requireAllRequestedKeys());
            Set<String> nextFrontier =
                    resolveCurrentLevel(toResolve, responseByKey, visitedKeys, terminalNodes, depth, execution);
            return traverseLevel(nextFrontier, visitedKeys, terminalNodes, depth + 1, execution);
        });
    }

    private static Set<String> resolveCurrentLevel(
            Set<String> toResolve,
            Map<String, JsonNode> responseByKey,
            Set<String> visitedKeys,
            Map<String, TraversalNode> terminalNodes,
            int depth,
            TraversalExecution execution) {
        Set<String> nextFrontier = new LinkedHashSet<>();
        LevelResolution resolution = new LevelResolution(
                visitedKeys,
                terminalNodes,
                nextFrontier,
                depth,
                execution.childKeyExtractor(),
                execution.isTerminalNode());
        for (String key : toResolve) {
            JsonNode node = responseByKey.get(key);
            if (node != null) {
                resolveNode(key, node, resolution);
            }
        }
        return nextFrontier;
    }

    private record LevelResolution(
            Set<String> visitedKeys,
            Map<String, TraversalNode> terminalNodes,
            Set<String> nextFrontier,
            int depth,
            ChildKeyExtractor childKeyExtractor,
            Predicate<JsonNode> isTerminalNode) {}

    private record TraversalExecution(
            TraversalPolicy policy,
            BatchFetcher batchFetcher,
            ChildKeyExtractor childKeyExtractor,
            Predicate<JsonNode> isTerminalNode) {

        private TraversalExecution {
            Objects.requireNonNull(policy, "policy");
            Objects.requireNonNull(batchFetcher, "batchFetcher");
            Objects.requireNonNull(childKeyExtractor, "childKeyExtractor");
            Objects.requireNonNull(isTerminalNode, "isTerminalNode");
        }
    }

    private static void resolveNode(String key, JsonNode node, LevelResolution resolution) {
        resolution.visitedKeys().add(key);
        if (resolution.isTerminalNode().test(node)) {
            resolution.terminalNodes().putIfAbsent(key, new TraversalNode(key, node, resolution.depth()));
            return;
        }
        addChildKeys(resolution.childKeyExtractor().childKeys(node), resolution.nextFrontier());
    }

    private static void addChildKeys(Iterable<String> childKeys, Set<String> nextFrontier) {
        for (String childKey : childKeys) {
            if (!childKey.isBlank()) {
                nextFrontier.add(childKey);
            }
        }
    }

    private static Set<String> normalizeKeys(Iterable<String> keys) {
        Set<String> out = new LinkedHashSet<>();
        for (String key : keys) {
            if (!key.isBlank()) {
                out.add(key);
            }
        }
        return out;
    }

    private static void requireAllRequestedKeys(
            Set<String> requestedKeys, @Nullable Map<String, JsonNode> responseByKey, boolean required) {
        if (responseByKey == null) {
            throw TraversalException.contractViolation("Traversal response map is null");
        }
        if (!required) {
            return;
        }
        for (String key : requestedKeys) {
            if (!responseByKey.containsKey(key) || responseByKey.get(key) == null) {
                throw TraversalException.contractViolation("Traversal response is missing requested key: " + key);
            }
        }
    }

    public static final class TraversalException extends RuntimeException {

        public enum Reason {
            DEPTH_EXCEEDED,
            CONTRACT_VIOLATION
        }

        private final Reason reason;

        private TraversalException(Reason reason, String message) {
            super(message);
            this.reason = reason;
        }

        public Reason reason() {
            return reason;
        }

        static TraversalException depthExceeded(int maxDepth) {
            return new TraversalException(Reason.DEPTH_EXCEEDED, "Traversal exceeds maxDepth " + maxDepth);
        }

        static TraversalException contractViolation(String message) {
            return new TraversalException(Reason.CONTRACT_VIOLATION, message);
        }
    }
}
