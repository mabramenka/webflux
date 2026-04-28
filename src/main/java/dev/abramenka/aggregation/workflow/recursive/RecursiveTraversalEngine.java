package dev.abramenka.aggregation.workflow.recursive;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
            Iterable<String> initialKeys,
            TraversalPolicy policy,
            BatchFetcher batchFetcher,
            ChildKeyExtractor childKeyExtractor,
            Predicate<JsonNode> isTerminalNode) {
        return traverse(initialKeys, policy, batchFetcher, childKeyExtractor, isTerminalNode, null);
    }

    public Mono<TraversalResult> traverse(
            Iterable<String> initialKeys,
            TraversalPolicy policy,
            BatchFetcher batchFetcher,
            ChildKeyExtractor childKeyExtractor,
            Predicate<JsonNode> isTerminalNode,
            @Nullable JsonNode targetMetadata) {
        Objects.requireNonNull(initialKeys, "initialKeys");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(batchFetcher, "batchFetcher");
        Objects.requireNonNull(childKeyExtractor, "childKeyExtractor");
        Objects.requireNonNull(isTerminalNode, "isTerminalNode");
        Set<String> frontier = normalizeKeys(initialKeys);
        return traverseLevel(
                        frontier,
                        new LinkedHashSet<>(),
                        new LinkedHashMap<>(),
                        1,
                        policy,
                        batchFetcher,
                        childKeyExtractor,
                        isTerminalNode)
                .map(terminalNodes -> new TraversalResult(new ArrayList<>(terminalNodes.values()), targetMetadata));
    }

    private Mono<Map<String, TraversalNode>> traverseLevel(
            Set<String> frontier,
            Set<String> visitedKeys,
            Map<String, TraversalNode> terminalNodes,
            int depth,
            TraversalPolicy policy,
            BatchFetcher batchFetcher,
            ChildKeyExtractor childKeyExtractor,
            Predicate<JsonNode> isTerminalNode) {
        if (frontier.isEmpty()) {
            return Mono.just(terminalNodes);
        }
        if (depth > policy.maxDepth()) {
            return Mono.error(TraversalException.depthExceeded(policy.maxDepth()));
        }
        LinkedHashSet<String> toResolve = new LinkedHashSet<>(frontier);
        switch (policy.cyclePolicy()) {
            case SKIP_VISITED -> toResolve.removeAll(visitedKeys);
        }
        if (toResolve.isEmpty()) {
            return Mono.just(terminalNodes);
        }
        return batchFetcher.fetchBatch(toResolve).flatMap(responseByKey -> {
            requireAllRequestedKeys(toResolve, responseByKey, policy.requireAllRequestedKeys());
            Set<String> nextFrontier = new LinkedHashSet<>();
            for (String key : toResolve) {
                JsonNode node = responseByKey.get(key);
                if (node == null) {
                    continue;
                }
                visitedKeys.add(key);
                if (isTerminalNode.test(node)) {
                    terminalNodes.putIfAbsent(key, new TraversalNode(key, node, depth));
                } else {
                    Iterable<String> childKeys = childKeyExtractor.childKeys(node);
                    if (childKeys == null) {
                        continue;
                    }
                    for (String childKey : childKeys) {
                        if (childKey != null && !childKey.isBlank()) {
                            nextFrontier.add(childKey);
                        }
                    }
                }
            }
            return traverseLevel(
                    nextFrontier,
                    visitedKeys,
                    terminalNodes,
                    depth + 1,
                    policy,
                    batchFetcher,
                    childKeyExtractor,
                    isTerminalNode);
        });
    }

    private static Set<String> normalizeKeys(Iterable<String> keys) {
        Set<String> out = new LinkedHashSet<>();
        for (String key : keys) {
            if (key != null && !key.isBlank()) {
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
