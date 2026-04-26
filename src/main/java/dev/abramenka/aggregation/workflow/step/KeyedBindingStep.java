package dev.abramenka.aggregation.workflow.step;

import dev.abramenka.aggregation.enrichment.support.keyed.PathExpression;
import dev.abramenka.aggregation.error.DownstreamClientException;
import dev.abramenka.aggregation.error.OrchestrationException;
import dev.abramenka.aggregation.model.AggregationContext;
import dev.abramenka.aggregation.model.PartSkipReason;
import dev.abramenka.aggregation.patch.JsonPatchBuilder;
import dev.abramenka.aggregation.patch.JsonPatchDocument;
import dev.abramenka.aggregation.patch.JsonPatchException;
import dev.abramenka.aggregation.workflow.StepResult;
import dev.abramenka.aggregation.workflow.WorkflowContext;
import dev.abramenka.aggregation.workflow.WorkflowStep;
import dev.abramenka.aggregation.workflow.binding.DownstreamBinding;
import dev.abramenka.aggregation.workflow.binding.KeySource;
import dev.abramenka.aggregation.workflow.binding.WriteRule;
import dev.abramenka.aggregation.workflow.binding.support.ExtractedTarget;
import dev.abramenka.aggregation.workflow.binding.support.KeyExtractor;
import dev.abramenka.aggregation.workflow.binding.support.MatchedTarget;
import dev.abramenka.aggregation.workflow.binding.support.ResponseIndexer;
import dev.abramenka.aggregation.workflow.binding.support.TargetMatcher;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * A {@link WorkflowStep} that implements the keyed enrichment pattern driven by a single {@link
 * DownstreamBinding}:
 *
 * <ol>
 *   <li>Resolve the key source from {@link WorkflowContext}: ROOT_SNAPSHOT, CURRENT_ROOT, or
 *       STEP_RESULT.
 *   <li>Extract targets and deduplicate keys using {@link KeyExtractor}.
 *   <li>If no keys — return {@code SKIPPED / NO_KEYS_IN_MAIN}.
 *   <li>Call the binding's downstream call.
 *   <li>Handle empty/404 downstream responses as soft outcomes.
 *   <li>Index the response via {@link ResponseIndexer}.
 *   <li>Match extracted targets against the indexed response via {@link TargetMatcher}.
 *   <li>Emit a {@link JsonPatchDocument} from the matches using the binding's {@link WriteRule}.
 * </ol>
 *
 * <p>Supported key sources: ROOT_SNAPSHOT, CURRENT_ROOT, STEP_RESULT. TRAVERSAL_STATE is rejected
 * at construction. STEP_RESULT with a non-null WriteRule is also rejected (fetch-only restriction
 * for Phase 11 — write targets in a step-result document do not map to global root paths).
 *
 * <p>{@link WriteRule.WriteAction.AppendToArray}: auto-creates the array when absent; fails if
 * field exists but is not an array.
 *
 * <p>{@link WriteRule.WriteAction.ReplaceField}: uses {@code add} when absent, {@code replace}
 * when present.
 */
public final class KeyedBindingStep implements WorkflowStep {

    private static final KeyExtractor KEY_EXTRACTOR = new KeyExtractor();
    private static final ResponseIndexer RESPONSE_INDEXER = new ResponseIndexer();
    private static final TargetMatcher TARGET_MATCHER = new TargetMatcher();

    private final String name;
    private final DownstreamBinding binding;

    /** {@code null} for store-only bindings that carry no {@link WriteRule}. */
    @Nullable
    private final PathExpression targetItemPath;

    public KeyedBindingStep(String name, DownstreamBinding binding) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("KeyedBindingStep name must not be blank");
        }
        Objects.requireNonNull(binding, "binding");
        requireSupportedSource(binding);
        requireMatchByWhenWriteRulePresent(binding);
        this.name = name;
        this.binding = binding;
        this.targetItemPath = binding.writeRule() != null
                ? PathExpression.parse(binding.writeRule().targetItemPath())
                : null;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Optional<String> bindingName() {
        return Optional.of(binding.name().value());
    }

    @Override
    public Optional<String> writtenFieldName() {
        WriteRule writeRule = binding.writeRule();
        return writeRule != null ? Optional.of(writeRule.action().fieldName()) : Optional.empty();
    }

    @Override
    public Mono<StepResult> execute(WorkflowContext context) {
        JsonNode source = resolveSource(
                binding.keyExtraction().source(), binding.keyExtraction().stepResultName(), context);
        AggregationContext aggCtx = context.aggregationContext();

        List<ExtractedTarget> targets = KEY_EXTRACTOR.extract(binding.keyExtraction(), source);
        Set<String> keys = deduplicateKeys(targets);

        if (keys.isEmpty()) {
            return Mono.just(StepResult.skipped(PartSkipReason.NO_KEYS_IN_MAIN));
        }

        return binding.downstreamCall()
                .fetch(List.copyOf(keys), aggCtx)
                .flatMap(response -> Mono.fromCallable(() -> processResponse(response, targets, source)))
                .switchIfEmpty(Mono.fromSupplier(() -> StepResult.empty(PartSkipReason.DOWNSTREAM_EMPTY)))
                .onErrorResume(
                        DownstreamClientException.class,
                        ex -> isNotFound(ex)
                                ? Mono.just(StepResult.empty(PartSkipReason.DOWNSTREAM_NOT_FOUND))
                                : Mono.error(ex));
    }

    private StepResult processResponse(JsonNode response, List<ExtractedTarget> targets, JsonNode source) {
        Map<String, JsonNode> indexedResponse = RESPONSE_INDEXER.index(binding.responseIndexing(), response);
        List<MatchedTarget> matches = TARGET_MATCHER.match(targets, indexedResponse);

        WriteRule writeRule = binding.writeRule();
        String storeAs = binding.storeAs();

        if (writeRule == null) {
            // store-only binding: fetch and persist, no patch produced
            return StepResult.stored(Objects.requireNonNull(storeAs, "storeAs"), response);
        }

        if (matches.isEmpty()) {
            return storeAs != null
                    ? StepResult.stored(storeAs, response)
                    : StepResult.empty(PartSkipReason.DOWNSTREAM_EMPTY);
        }

        JsonPatchDocument patch = buildPatch(matches, writeRule, source);

        if (!patch.isEmpty()) {
            return storeAs != null ? StepResult.applied(patch, storeAs, response) : StepResult.applied(patch);
        }

        return storeAs != null
                ? StepResult.stored(storeAs, response)
                : StepResult.empty(PartSkipReason.DOWNSTREAM_EMPTY);
    }

    private JsonPatchDocument buildPatch(List<MatchedTarget> matches, WriteRule writeRule, JsonNode source) {
        PathExpression itemPath = Objects.requireNonNull(targetItemPath, "targetItemPath");

        // Build identity index: owner ObjectNode → index within the same source document so that
        // the JSON Pointer paths are consistent for application to the working document.
        List<JsonNode> writeTargetItems = itemPath.select(source);
        IdentityHashMap<ObjectNode, Integer> ownerToIndex = new IdentityHashMap<>();
        for (int i = 0; i < writeTargetItems.size(); i++) {
            if (writeTargetItems.get(i) instanceof ObjectNode obj) {
                ownerToIndex.put(obj, i);
            }
        }

        JsonPatchBuilder patchBuilder = JsonPatchBuilder.create();
        Set<ObjectNode> arrayCreatedForOwner = Collections.newSetFromMap(new IdentityHashMap<>());

        for (MatchedTarget match : matches) {
            Integer idx = ownerToIndex.get(match.owner());
            if (idx == null) {
                continue;
            }
            String basePointer = itemPath.toItemPointerAt(idx);
            applyWriteAction(
                    patchBuilder,
                    basePointer,
                    writeRule.action(),
                    match.owner(),
                    match.responseEntry(),
                    arrayCreatedForOwner);
        }

        return patchBuilder.build();
    }

    private static void applyWriteAction(
            JsonPatchBuilder builder,
            String basePointer,
            WriteRule.WriteAction action,
            ObjectNode owner,
            JsonNode responseEntry,
            Set<ObjectNode> arrayCreatedForOwner) {
        switch (action) {
            case WriteRule.WriteAction.ReplaceField rf -> {
                String pointer = basePointer + "/" + escapeToken(rf.fieldName());
                if (owner.has(rf.fieldName())) {
                    builder.replace(pointer, responseEntry);
                } else {
                    builder.add(pointer, responseEntry);
                }
            }
            case WriteRule.WriteAction.AppendToArray aa -> {
                String fieldPointer = basePointer + "/" + escapeToken(aa.fieldName());
                JsonNode existing = owner.get(aa.fieldName());
                if (existing != null && !existing.isArray()) {
                    throw OrchestrationException.mergeFailed(new JsonPatchException(
                            "AppendToArray: field '" + aa.fieldName() + "' exists but is not an array in target item"));
                }
                if (existing == null && !arrayCreatedForOwner.contains(owner)) {
                    builder.add(fieldPointer, JsonNodeFactory.instance.arrayNode());
                    arrayCreatedForOwner.add(owner);
                }
                builder.add(fieldPointer + "/-", responseEntry);
            }
        }
    }

    private static JsonNode resolveSource(KeySource source, @Nullable String stepResultName, WorkflowContext context) {
        return switch (source) {
            case ROOT_SNAPSHOT -> context.rootSnapshot();
            case CURRENT_ROOT -> context.currentRoot();
            case STEP_RESULT ->
                context.variables()
                        .get(Objects.requireNonNull(stepResultName, "stepResultName"))
                        .orElseThrow(() -> OrchestrationException.invariantViolated(new IllegalStateException(
                                "STEP_RESULT '" + stepResultName + "' not found in workflow variables; "
                                        + "the producing step must have run and stored a value before this step")));
            case TRAVERSAL_STATE ->
                throw new IllegalStateException("TRAVERSAL_STATE is not supported in KeyedBindingStep");
        };
    }

    private static Set<String> deduplicateKeys(List<ExtractedTarget> targets) {
        Set<String> keys = new LinkedHashSet<>();
        for (ExtractedTarget t : targets) {
            keys.add(t.key());
        }
        return keys;
    }

    private static void requireSupportedSource(DownstreamBinding binding) {
        KeySource source = binding.keyExtraction().source();
        if (source == KeySource.TRAVERSAL_STATE) {
            throw new IllegalArgumentException(
                    "KeyedBindingStep does not support TRAVERSAL_STATE; use RecursiveFetchStep (Phase 14)");
        }
        if (source == KeySource.STEP_RESULT && binding.writeRule() != null) {
            throw new IllegalArgumentException(
                    "KeyedBindingStep with source=STEP_RESULT must be fetch-only (writeRule must be null); "
                            + "writes from a step-result document do not map to global root paths");
        }
    }

    private static void requireMatchByWhenWriteRulePresent(DownstreamBinding binding) {
        WriteRule writeRule = binding.writeRule();
        if (writeRule != null && writeRule.matchBy() == null) {
            throw new IllegalArgumentException(
                    "KeyedBindingStep requires WriteRule.matchBy to be set for keyed matching");
        }
    }

    private static boolean isNotFound(DownstreamClientException ex) {
        return ex.downstreamStatusCode() != null && ex.downstreamStatusCode().value() == HttpStatus.NOT_FOUND.value();
    }

    private static String escapeToken(String token) {
        if (token.indexOf('~') < 0 && token.indexOf('/') < 0) {
            return token;
        }
        return token.replace("~", "~0").replace("/", "~1");
    }
}
