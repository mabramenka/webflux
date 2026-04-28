package dev.abramenka.aggregation.workflow.recursive;

import dev.abramenka.aggregation.workflow.StepResult;
import dev.abramenka.aggregation.workflow.WorkflowContext;
import dev.abramenka.aggregation.workflow.WorkflowStep;
import dev.abramenka.aggregation.workflow.binding.KeyExtractionRule;
import dev.abramenka.aggregation.workflow.binding.KeySource;
import dev.abramenka.aggregation.workflow.binding.support.KeyExtractor;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * Thin workflow-step adapter that runs recursive traversal and stores the result as STEP_RESULT.
 */
public final class RecursiveFetchStep implements WorkflowStep {

    private static final KeyExtractor KEY_EXTRACTOR = new KeyExtractor();

    @FunctionalInterface
    public interface TargetMetadataExtractor {
        @Nullable
        JsonNode extract(JsonNode source);
    }

    private final String name;
    private final String storeAs;
    private final KeyExtractionRule seedExtraction;
    private final TraversalPolicy traversalPolicy;
    private final RecursiveTraversalEngine traversalEngine;
    private final RecursiveTraversalEngine.BatchFetcher batchFetcher;
    private final RecursiveTraversalEngine.ChildKeyExtractor childKeyExtractor;
    private final Predicate<JsonNode> isTerminalNode;

    @Nullable
    private final TargetMetadataExtractor targetMetadataExtractor;

    public RecursiveFetchStep(
            String name,
            String storeAs,
            KeyExtractionRule seedExtraction,
            TraversalPolicy traversalPolicy,
            RecursiveTraversalEngine traversalEngine,
            RecursiveTraversalEngine.BatchFetcher batchFetcher,
            RecursiveTraversalEngine.ChildKeyExtractor childKeyExtractor,
            Predicate<JsonNode> isTerminalNode) {
        this(
                name,
                storeAs,
                seedExtraction,
                traversalPolicy,
                traversalEngine,
                batchFetcher,
                childKeyExtractor,
                isTerminalNode,
                null);
    }

    public RecursiveFetchStep(
            String name,
            String storeAs,
            KeyExtractionRule seedExtraction,
            TraversalPolicy traversalPolicy,
            RecursiveTraversalEngine traversalEngine,
            RecursiveTraversalEngine.BatchFetcher batchFetcher,
            RecursiveTraversalEngine.ChildKeyExtractor childKeyExtractor,
            Predicate<JsonNode> isTerminalNode,
            @Nullable TargetMetadataExtractor targetMetadataExtractor) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("RecursiveFetchStep name must not be blank");
        }
        if (storeAs == null || storeAs.isBlank()) {
            throw new IllegalArgumentException("RecursiveFetchStep storeAs must not be blank");
        }
        Objects.requireNonNull(seedExtraction, "seedExtraction");
        requireSupportedSource(seedExtraction.source());
        Objects.requireNonNull(traversalPolicy, "traversalPolicy");
        Objects.requireNonNull(traversalEngine, "traversalEngine");
        Objects.requireNonNull(batchFetcher, "batchFetcher");
        Objects.requireNonNull(childKeyExtractor, "childKeyExtractor");
        Objects.requireNonNull(isTerminalNode, "isTerminalNode");

        this.name = name;
        this.storeAs = storeAs;
        this.seedExtraction = seedExtraction;
        this.traversalPolicy = traversalPolicy;
        this.traversalEngine = traversalEngine;
        this.batchFetcher = batchFetcher;
        this.childKeyExtractor = childKeyExtractor;
        this.isTerminalNode = isTerminalNode;
        this.targetMetadataExtractor = targetMetadataExtractor;
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
        JsonNode source = resolveSource(context, seedExtraction.source());
        Set<String> initialSeeds = KEY_EXTRACTOR.distinctKeys(seedExtraction, source);
        JsonNode targetMetadata = targetMetadataExtractor == null ? null : targetMetadataExtractor.extract(source);

        return traversalEngine
                .traverse(
                        initialSeeds, traversalPolicy, batchFetcher, childKeyExtractor, isTerminalNode, targetMetadata)
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
