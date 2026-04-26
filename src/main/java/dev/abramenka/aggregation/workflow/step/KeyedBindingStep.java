package dev.abramenka.aggregation.workflow.step;

import dev.abramenka.aggregation.enrichment.support.keyed.PathExpression;
import dev.abramenka.aggregation.error.DownstreamClientException;
import dev.abramenka.aggregation.error.OrchestrationException;
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
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A {@link WorkflowStep} that implements the simple keyed enrichment pattern driven by a single
 * {@link DownstreamBinding}:
 *
 * <ol>
 *   <li>Read keys from {@code ROOT_SNAPSHOT} via {@link KeyExtractor}.
 *   <li>Deduplicate request keys preserving first-seen order.
 *   <li>If no keys — return {@code SKIPPED / NO_KEYS_IN_MAIN}.
 *   <li>Call the binding's {@link dev.abramenka.aggregation.workflow.binding.DownstreamCall}.
 *   <li>Handle empty/404 downstream responses as soft outcomes.
 *   <li>Index the response via {@link ResponseIndexer}.
 *   <li>Match extracted targets against the indexed response via {@link TargetMatcher}.
 *   <li>Emit a {@link JsonPatchDocument} from the matches using the binding's {@link WriteRule}.
 * </ol>
 *
 * <p>Only {@link KeySource#ROOT_SNAPSHOT} is supported in this phase. {@code CURRENT_ROOT},
 * {@code STEP_RESULT}, and {@code TRAVERSAL_STATE} are rejected at construction.
 *
 * <p>{@link WriteRule.WriteAction.AppendToArray}: the target array field must already exist in the
 * matched owner item; automatic creation is not supported in this phase.
 *
 * <p>{@link WriteRule.WriteAction.ReplaceField}: uses {@code add} when the field is absent and
 * {@code replace} when it already exists.
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

    /**
     * @throws IllegalArgumentException if {@code name} is blank, {@code binding} is null, the
     *     binding's {@link KeySource} is not {@link KeySource#ROOT_SNAPSHOT}, or the binding has a
     *     non-null {@link WriteRule} without a {@link WriteRule.MatchBy}
     */
    public KeyedBindingStep(String name, DownstreamBinding binding) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("KeyedBindingStep name must not be blank");
        }
        Objects.requireNonNull(binding, "binding");
        requireRootSnapshotSource(binding);
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
    public Mono<StepResult> execute(WorkflowContext context) {
        ObjectNode rootSnapshot = context.rootSnapshot();
        List<ExtractedTarget> targets = KEY_EXTRACTOR.extract(binding.keyExtraction(), rootSnapshot);
        Set<String> keys = deduplicateKeys(targets);

        if (keys.isEmpty()) {
            return Mono.just(StepResult.skipped(PartSkipReason.NO_KEYS_IN_MAIN));
        }

        return binding.downstreamCall()
                .fetch(List.copyOf(keys))
                .flatMap(response -> Mono.fromCallable(() -> processResponse(response, targets, rootSnapshot)))
                .switchIfEmpty(Mono.fromSupplier(() -> StepResult.empty(PartSkipReason.DOWNSTREAM_EMPTY)))
                .onErrorResume(
                        DownstreamClientException.class,
                        ex -> isNotFound(ex)
                                ? Mono.just(StepResult.empty(PartSkipReason.DOWNSTREAM_NOT_FOUND))
                                : Mono.error(ex));
    }

    private StepResult processResponse(JsonNode response, List<ExtractedTarget> targets, ObjectNode rootSnapshot) {
        Map<String, JsonNode> indexedResponse = RESPONSE_INDEXER.index(binding.responseIndexing(), response);
        List<MatchedTarget> matches = TARGET_MATCHER.match(targets, indexedResponse);

        WriteRule writeRule = binding.writeRule();
        String storeAs = binding.storeAs();

        if (writeRule == null) {
            // store-only binding: fetch and persist, no patch produced
            return StepResult.stored(Objects.requireNonNull(storeAs, "storeAs"), response);
        }

        if (matches.isEmpty()) {
            // Downstream returned data but no target item matched — soft empty outcome
            return storeAs != null
                    ? StepResult.stored(storeAs, response)
                    : StepResult.empty(PartSkipReason.DOWNSTREAM_EMPTY);
        }

        JsonPatchDocument patch = buildPatch(matches, writeRule, rootSnapshot);

        if (!patch.isEmpty()) {
            return storeAs != null ? StepResult.applied(patch, storeAs, response) : StepResult.applied(patch);
        }

        // Matches existed but yielded no patch ops (e.g. all owners skipped due to index mismatch)
        return storeAs != null
                ? StepResult.stored(storeAs, response)
                : StepResult.empty(PartSkipReason.DOWNSTREAM_EMPTY);
    }

    private JsonPatchDocument buildPatch(List<MatchedTarget> matches, WriteRule writeRule, ObjectNode rootSnapshot) {
        PathExpression itemPath = Objects.requireNonNull(targetItemPath, "targetItemPath");

        // Map each target-item ObjectNode to its index in the write-target array by identity so we
        // can build the JSON Pointer without re-parsing keys.
        List<JsonNode> writeTargetItems = itemPath.select(rootSnapshot);
        IdentityHashMap<ObjectNode, Integer> ownerToIndex = new IdentityHashMap<>();
        for (int i = 0; i < writeTargetItems.size(); i++) {
            if (writeTargetItems.get(i) instanceof ObjectNode obj) {
                ownerToIndex.put(obj, i);
            }
        }

        JsonPatchBuilder patchBuilder = JsonPatchBuilder.create();
        for (MatchedTarget match : matches) {
            Integer idx = ownerToIndex.get(match.owner());
            if (idx == null) {
                // Owner not found in write-target path — source and target paths differ.
                // Silently skipped for Phase 7; Phase 11 will address multi-target writes.
                continue;
            }
            String basePointer = itemPath.toItemPointerAt(idx);
            applyWriteAction(patchBuilder, basePointer, writeRule.action(), match.owner(), match.responseEntry());
        }

        return patchBuilder.build();
    }

    private static void applyWriteAction(
            JsonPatchBuilder builder,
            String basePointer,
            WriteRule.WriteAction action,
            ObjectNode owner,
            JsonNode responseEntry) {
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
                JsonNode existing = owner.get(aa.fieldName());
                if (existing == null || !existing.isArray()) {
                    throw OrchestrationException.mergeFailed(new JsonPatchException(
                            "AppendToArray: field '" + aa.fieldName() + "' does not exist or is not an array"
                                    + " in target item; automatic array creation is not supported"
                                    + " in KeyedBindingStep"));
                }
                String pointer = basePointer + "/" + escapeToken(aa.fieldName()) + "/-";
                builder.add(pointer, responseEntry);
            }
        }
    }

    private static Set<String> deduplicateKeys(List<ExtractedTarget> targets) {
        Set<String> keys = new LinkedHashSet<>();
        for (ExtractedTarget t : targets) {
            keys.add(t.key());
        }
        return keys;
    }

    private static void requireRootSnapshotSource(DownstreamBinding binding) {
        KeySource source = binding.keyExtraction().source();
        if (source != KeySource.ROOT_SNAPSHOT) {
            throw new IllegalArgumentException(
                    "KeyedBindingStep supports only ROOT_SNAPSHOT in this phase; got: " + source);
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
