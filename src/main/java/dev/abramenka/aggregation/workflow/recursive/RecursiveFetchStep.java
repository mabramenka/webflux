package dev.abramenka.aggregation.workflow.recursive;

import dev.abramenka.aggregation.workflow.StepResult;
import dev.abramenka.aggregation.workflow.WorkflowContext;
import dev.abramenka.aggregation.workflow.WorkflowStep;
import dev.abramenka.aggregation.workflow.binding.KeySource;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * Thin workflow-step adapter that runs recursive traversal and stores the result as STEP_RESULT.
 */
public final class RecursiveFetchStep implements WorkflowStep {

    @FunctionalInterface
    public interface TraversalSeedGroupExtractor {
        List<TraversalSeedGroup> extract(JsonNode source);
    }

    private final String name;
    private final String storeAs;
    private final KeySource seedSource;
    private final TraversalSeedGroupExtractor seedGroupExtractor;
    private final TraversalPolicy traversalPolicy;
    private final RecursiveTraversalEngine traversalEngine;
    private final RecursiveTraversalEngine.BatchFetcher batchFetcher;
    private final RecursiveTraversalEngine.ChildKeyExtractor childKeyExtractor;
    private final Predicate<JsonNode> isTerminalNode;

    public RecursiveFetchStep(
            String name,
            String storeAs,
            KeySource seedSource,
            TraversalSeedGroupExtractor seedGroupExtractor,
            TraversalPolicy traversalPolicy,
            RecursiveTraversalEngine traversalEngine,
            RecursiveTraversalEngine.BatchFetcher batchFetcher,
            RecursiveTraversalEngine.ChildKeyExtractor childKeyExtractor,
            Predicate<JsonNode> isTerminalNode) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("RecursiveFetchStep name must not be blank");
        }
        if (storeAs == null || storeAs.isBlank()) {
            throw new IllegalArgumentException("RecursiveFetchStep storeAs must not be blank");
        }
        Objects.requireNonNull(seedSource, "seedSource");
        requireSupportedSource(seedSource);
        Objects.requireNonNull(seedGroupExtractor, "seedGroupExtractor");
        Objects.requireNonNull(traversalPolicy, "traversalPolicy");
        Objects.requireNonNull(traversalEngine, "traversalEngine");
        Objects.requireNonNull(batchFetcher, "batchFetcher");
        Objects.requireNonNull(childKeyExtractor, "childKeyExtractor");
        Objects.requireNonNull(isTerminalNode, "isTerminalNode");

        this.name = name;
        this.storeAs = storeAs;
        this.seedSource = seedSource;
        this.seedGroupExtractor = seedGroupExtractor;
        this.traversalPolicy = traversalPolicy;
        this.traversalEngine = traversalEngine;
        this.batchFetcher = batchFetcher;
        this.childKeyExtractor = childKeyExtractor;
        this.isTerminalNode = isTerminalNode;
    }

    @Override
    public String name() {
        return name;
    }

    public String storeAs() {
        return storeAs;
    }

    @Override
    public Mono<StepResult> execute(WorkflowContext context) {
        JsonNode source = resolveSource(context, seedSource);
        List<TraversalSeedGroup> seedGroups = Objects.requireNonNull(seedGroupExtractor.extract(source), "seedGroups");

        return traversalEngine
                .traverse(seedGroups, traversalPolicy, batchFetcher, childKeyExtractor, isTerminalNode)
                .map(result -> StepResult.stored(storeAs, result.toJsonNode(JsonNodeFactory.instance)));
    }

    private static JsonNode resolveSource(WorkflowContext context, KeySource source) {
        return switch (source) {
            case ROOT_SNAPSHOT -> context.rootSnapshot();
            case CURRENT_ROOT -> context.currentRoot();
            case STEP_RESULT, TRAVERSAL_STATE ->
                throw new IllegalStateException("RecursiveFetchStep supports only ROOT_SNAPSHOT and CURRENT_ROOT");
        };
    }

    private static void requireSupportedSource(KeySource source) {
        if (source == KeySource.STEP_RESULT || source == KeySource.TRAVERSAL_STATE) {
            throw new IllegalArgumentException(
                    "RecursiveFetchStep supports only ROOT_SNAPSHOT and CURRENT_ROOT seed sources");
        }
    }
}
