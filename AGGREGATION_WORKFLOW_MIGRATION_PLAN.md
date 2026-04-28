# Aggregation Gateway Workflow Migration Plan

> **Purpose:** This document is a sequential, agent-friendly migration plan for evolving the current Aggregation Gateway from hand-written enrichment parts into a more general, workflow-capable enrichment architecture.
>
> **Primary goal:** adding a new enrichment should require only a small set of descriptive classes: part name, dependencies, downstream bindings, endpoint-specific key extraction rules, response indexing rules, write/patch rules, and optional compute/reduce logic. The existing engine should automatically handle planning, execution, errors, metrics, and JSON output mutation.
>
> **Current status:** Phase 1 through Phase 15 are completed on `main`. The post-Phase-13 safety update is present: migrated `account` and `owners` workflow parts declare `WriteOwnership`. BeneficialOwners legacy contract lock is completed (Phase 13.5), recursive traversal skeleton work is completed (Phase 14A/14B/14C), and the traversal reducer/write-back bridge is completed (Phase 15). Phase 16 — migrate beneficial owners — is the next implementation phase.
>
> **Current working branch:** migration work is currently being continued directly on `main`. If this changes, update this document before starting the next phase.

---

## 1. Required Agent Operating Rules

Before implementing any task from this document, every coding agent must apply the common engineering rules from:

```text
https://github.com/forrestchang/andrej-karpathy-skills/blob/main/CLAUDE.md
```

If that repository is not accessible, apply the summarized rules below as the authoritative fallback.

Use those rules as the default working style for every phase:

```text
1. Think before coding.
2. State assumptions explicitly.
3. Prefer the simplest working design.
4. Make surgical changes only.
5. Do not refactor unrelated code.
6. Define success criteria before editing.
7. Verify with tests.
8. Stop and report uncertainty instead of guessing.
```

### Project-specific interpretation of those rules

For this migration, the rules mean:

```text
- Do not rewrite the aggregation engine.
- Do not introduce a big-bang architecture replacement.
- Keep old AggregationPart implementations working while new workflow parts are introduced.
- Add new abstractions beside existing code first.
- Migrate one enrichment at a time.
- Keep public response contracts stable unless a phase explicitly says otherwise.
- Keep RFC 9457 problem responses stable.
- Keep success-side meta.parts stable.
- Prefer explicit tests over assumptions.
- Every phase must be independently understandable and revertible as a commit or small commit group.
```

---

## 2. Repository, Branch, Commit, and Test Protocol

This section reflects the current repository state after Phases 7 through 13 were merged directly into `main`. It also reflects the small post-Phase-13 safety update that declares `WriteOwnership` for the migrated `account` and `owners` workflow parts.

The migration is currently continued on `main`, not on a long-lived `workflow-engine` branch.

### Branch rule

```text
Current expected branch: main
```

Rules:

```text
- Continue the remaining phases sequentially.
- Do not create a separate PR per phase unless the repository owner explicitly asks for it.
- Commit completed phase work directly to main only if that is the current chosen workflow.
- If the workflow changes back to a feature branch, update this document before starting the next phase.
- After finishing one phase, update the tracker and add a handoff note.
- Keep phase boundaries visible through commit messages and plan tracker updates.
```

Before editing, the agent should verify:

```bash
git branch --show-current
git status --short
```

Expected branch for the current workflow:

```text
main
```

Do not rebase or rewrite history unless explicitly instructed by the repository owner.

### Commit rule

Each completed phase should be committed as one clear commit or a very small commit group.

Preferred commit message examples:

```text
feat(workflow): add keyed binding step
feat(workflow): migrate account enrichment to workflow
feat(workflow): add binding metrics
fix(workflow): harden patch conflict detection
docs(workflow): update migration plan tracker
```

Each phase commit or commit group must include a short handoff note in either:

```text
- the commit message body, or
- the migration plan tracker section, or
- a dedicated local handoff note if the repository already uses one.
```

The handoff note should answer:

```text
- What phase was completed?
- What files/classes were added or changed?
- What was intentionally not done?
- What local checks were run?
- What should the next phase start from?
```

### PR rule

```text
Do not create one PR per phase.
```

The phase plan still matters, but phases are now checkpoints in the current integration branch.

If the repository owner later decides to move the remaining work to a feature branch, update this section before continuing.

### Local test rule

Do not run the full global test suite after every tiny phase unless explicitly needed.

Per phase, run focused local checks that are relevant to the changed code.

Examples:

```bash
./gradlew test --tests '*KeyedBindingStepTest'
./gradlew test --tests '*WorkflowExecutorTest'
./gradlew test --tests '*Account*'
./gradlew spotlessJavaCheck
```

When touching build logic, dependency versions, classpath verification, or shared infrastructure, run the specific relevant checks too.

### CI rule

Every pushed commit to `main` should be left for CI to validate.

CI is allowed to run broader checks than the local focused checks.

Do not block each small phase locally on the entire global suite unless the changed area is risky enough to justify it.

### Global verification checkpoints

Run the full local/global verification only after a larger logical group of commits, or before the branch is prepared for integration.

Recommended batch checkpoints:

```text
- after Phase 7 and Phase 8
- after Phase 9 and Phase 10
- after Phase 11 through Phase 13
- after Phase 13.5 through Phase 16
- after Phase 17 through Phase 19
- before starting the next risky batch or after a direct-to-main phase group
```

Recommended full verification command set:

```bash
./gradlew test
./gradlew spotlessJavaCheck verifyBoot4Classpath jacocoTestCoverageVerification
./gradlew build
```

If a full verification step is skipped locally because CI is expected to cover it, say so explicitly in the handoff note.

---

## 3. Preferred Code Search and Navigation Protocol

`fff` is the preferred tool for local code search, repository navigation, and fast agent context gathering.

Agents should use `fff` before making structural assumptions about the codebase.

Typical examples:

```bash
fff "WorkflowExecutor"
fff "AggregationPartResult"
fff "NO_KEYS_IN_MAIN"
fff "DownstreamBinding"
fff "PartSkipReason"
fff "AggregationPartExecutor"
fff "PathExpression"
fff "KeyPathGroups"
```

Preferred workflow:

```text
1. Use fff to locate the relevant classes, tests, and usages.
2. Read the surrounding code before editing.
3. Prefer modifying the smallest set of files.
4. Add or update focused tests near the changed behavior.
5. Re-run fff after edits if class names, package names, or responsibilities moved.
```

Fallbacks are allowed only if `fff` is not installed or unavailable:

```bash
rg "WorkflowExecutor"
git grep "WorkflowExecutor"
find src -name '*Workflow*'
```

If a fallback is used, mention that in the handoff note.

Do not use broad blind refactors based only on search output. Always inspect the target code before editing.

---

## 4. Current Project Context

The current service is a reactive Spring Boot / WebFlux aggregation facade.

Conceptually, the current flow is:

```text
Inbound request
  -> validate request
  -> build ClientRequestContext from headers/query params
  -> plan selected AggregationPart instances
  -> call mandatory root/main downstream A
  -> execute selected enrichment parts by dependency levels
  -> merge enrichment results into root JSON
  -> attach meta.parts
  -> return enriched JSON
```

Current strong foundations that must be preserved:

```text
- AggregationPart plugin contract
- AggregationPartPlanner
- AggregationPartGraph / dependency expansion
- AggregationPartExecutor level-by-level execution
- same-level parallelism
- stable graph-order result application
- meta.parts outcome model
- RFC 9457-style external error contract
- dynamic JSON model using JsonNode/ObjectNode
- path-based keyed enrichment support
```

Current limitations this migration addresses:

```text
- Each business enrichment tends to hand-code orchestration details.
- Simple keyed joins are partly generalized, but multi-REST workflows are not.
- A part cannot yet describe multiple endpoint-specific downstream bindings cleanly.
- Key extraction is not yet a first-class binding-level concept.
- Compute/reduce steps are not first-class.
- Recursive traversal is business-specific rather than reusable.
- Write/merge semantics are implicit, usually based on base/replacement comparison.
- Internal RFC 6902-like patch operations are not first-class.
```

---

## 5. Target Architecture

The target architecture keeps `AggregationPart` as the public business-level plugin, while adding a workflow implementation style inside it.

```text
AggregateService
  -> AggregationPartPlanner
  -> AggregationPartExecutor
       -> AggregationPart
            -> existing hand-written part
            -> WorkflowAggregationPart
                 -> AggregationWorkflow
                      -> WorkflowStep
                      -> DownstreamBinding
                      -> JsonPatchDocument
```

### Key split

```text
AggregationPart
  Business-level enrichment visible to clients via include.
  Examples: account, owners, beneficialOwners, riskScore.

DownstreamBinding
  One concrete REST dependency inside a part.
  Each binding owns its own key extraction rules.
  Examples: account-service binding, owner-service binding, risk-service binding.

WorkflowStep
  Technical operation inside a workflow.
  Examples: extract keys, fetch JSON, index response, compute value, reduce traversal, write patch.

JsonPatchDocument
  Internal write model inspired by RFC 6902.
  Not exposed as public API.
```

### Most important design principle

```text
Do not replace AggregationPart with Workflow.

AggregationPart remains the business-level public unit.
Workflow is only one implementation strategy for AggregationPart.
Old hand-written parts and new workflow-based parts must coexist during migration.
```

### Second most important design principle

```text
Do not use one global key extractor per part.

Each downstream binding owns its own endpoint-specific key extraction rule,
because every REST dependency may require different keys from the same source document.
```

### Workflow state model

```text
ROOT_SNAPSHOT
  The immutable input document visible to the business part at the moment the part starts.

CURRENT_ROOT
  The workflow-local working document for one business part.
  It is derived from ROOT_SNAPSHOT by applying the patch operations accumulated by
  previous workflow steps of the same part.
  It is not the global mutable root owned by AggregationPartExecutor.

STEP_RESULT
  Named intermediate data produced by a workflow step or binding.
  It may be consumed by later bindings, compute steps, or reducers without forcing
  an immediate write into CURRENT_ROOT.
```

Important:

```text
ROOT_SNAPSHOT is scoped to the currently executing AggregationPart.

It is not necessarily the raw root document returned by the main dependency.
If the part depends on another part, then ROOT_SNAPSHOT is the root snapshot visible
after all previous dependency levels have been applied by AggregationPartExecutor.

Example:
beneficialOwners depends on owners, so its ROOT_SNAPSHOT already contains the owners
enrichment applied by the previous level.
```

```text
The global root document is still mutated only once, when the final AggregationPartResult
of the business part is applied by the existing AggregationPartExecutor flow.
```

### Path syntax rule

```text
Workflow path expressions reuse the existing narrow project-specific path dialect rather
than JSONPath.

Supported syntax:
- $
- dot-separated field access
- [*] array expansion

Out of scope for this migration:
- filters
- numeric indices such as [0]
- slices
- predicates
- recursive descent
- functions
```

---

## 6. Important Post-Phase-6 Clarifications

Phase 6 introduced the workflow skeleton, but it intentionally did not implement the full runtime semantics needed by later workflow phases.

The following clarifications are authoritative for all next phases.

### 6.1 Phase 6 is skeleton-complete, not feature-complete

Phase 6 completed the workflow-as-`AggregationPart` adapter and the basic workflow execution skeleton.

Completed in Phase 6:

```text
- AggregationWorkflow
- WorkflowAggregationPart
- WorkflowExecutor
- WorkflowContext
- WorkflowResult
- WorkflowVariableStore
- WorkflowStep
- StepResult
- WorkflowDefinitionValidator
```

Phase 6 intentionally does not yet provide:

```text
- KeyedBindingStep
- binding execution
- CURRENT_ROOT semantics
- concrete binding-level path validation
- concrete step-result reference validation
- compute steps
- recursive traversal
- binding metrics
```

Therefore, do not treat Phase 6 as permission to migrate an enrichment yet.

The first production-usable workflow step is Phase 7.

### 6.2 ROOT_SNAPSHOT must be treated as immutable

`ROOT_SNAPSHOT` means the input document visible to one business part at the moment that part starts.

Workflow steps must not mutate it directly.

Rules:

```text
- A workflow step may read ROOT_SNAPSHOT.
- A workflow step must not call ObjectNode.set/remove/removeAll/etc. on ROOT_SNAPSHOT.
- A workflow step must express writes through JsonPatchDocument only.
- The global root document must still be mutated only once by AggregationPartExecutor when the final AggregationPartResult is applied.
```

Implementation guidance:

```text
Prefer constructing WorkflowContext with a defensive deep copy of the AggregationContext root.

Good:
  new WorkflowContext(context, context.accountGroupResponse().deepCopy())

Risky:
  new WorkflowContext(context, context.accountGroupResponse())
```

If returning `rootSnapshot()` exposes a mutable `ObjectNode`, all concrete steps must still treat it as read-only by convention and tests must protect this behavior.

### 6.3 CURRENT_ROOT is not implemented yet

The target architecture defines:

```text
CURRENT_ROOT =
  workflow-local working document for one business part,
  derived from ROOT_SNAPSHOT by applying patch operations accumulated by previous steps
  of the same workflow.
```

As of Phase 6, `WorkflowExecutor` only accumulates patch operations and returns one final `JsonPatchDocument`.

It does not yet apply accumulated patches to a workflow-local working document between steps.

This is acceptable for Phase 7 if Phase 7 implements only a single keyed binding step reading from `ROOT_SNAPSHOT`.

It is not sufficient for Phase 11.

Phase 11 must implement or complete the real `CURRENT_ROOT` behavior before allowing later bindings to read from `CURRENT_ROOT`.

### 6.4 Empty patch must not silently mean business success for real bindings

An empty `JsonPatchDocument` is acceptable for tests and skeleton wiring.

For real enrichment logic, especially `KeyedBindingStep`, an empty patch must be treated deliberately.

Phase 7 must define and test the outcome for these cases:

```text
No extracted keys from ROOT_SNAPSHOT:
  SKIPPED / NO_KEYS_IN_MAIN

Downstream empty body:
  EMPTY / DOWNSTREAM_EMPTY

Downstream 404:
  EMPTY / DOWNSTREAM_NOT_FOUND

Downstream response contains items but produces no matched target writes:
  Do not silently return APPLIED with an empty patch.
  Choose an explicit existing outcome or fail with a contract violation,
  depending on the existing behavior of the enrichment being modeled.
```

Until a stronger rule is introduced, a workflow binding should return `StepResult.applied(patch)` only when it has at least one real write operation or a deliberately stored step result.

### 6.5 Workflow validation is staged

`WorkflowDefinitionValidator` currently validates only workflow-level structure such as blank and duplicate step names.

Concrete validation must be added as concrete step types appear.

Phase 7 must add validation for `KeyedBindingStep`, including at least:

```text
- valid binding name
- valid KeyExtractionRule
- supported KeySource for this phase
- valid response indexing rule
- valid write rule
- invalid path syntax fails before runtime use where possible
```

Later phases must add validation for:

```text
- STEP_RESULT references
- CURRENT_ROOT source usage
- compute inputs
- traversal configuration
- write ownership declarations
```

Do not add fake validation that only looks complete but does not actually validate concrete step behavior.

---

## 7. Desired Developer Experience

After the migration, adding a simple enrichment should look approximately like this:

```java
@Component
final class RiskScoreEnrichment extends WorkflowAggregationPart {

    RiskScoreEnrichment(RiskClient riskClient, WorkflowExecutor executor) {
        super(AggregationWorkflow.builder("riskScore")
            .dependsOn("account")
            .required()
            .binding("risk")
                .source(KeySource.ROOT_SNAPSHOT)
                .items("$.data[*]")
                .keys("accounts[*].id")
                .call(keys -> riskClient.fetchRisk(keys))
                .responseItems("$.data[*]")
                .responseKeys("accountId")
                .write()
                    .targetItems("$.data[*]")
                    .matchBy("accounts[*].id", "accountId")
                    .replaceField("riskScore")
                .endBinding()
            .build(), executor);
    }
}
```

For a multi-REST enrichment:

```java
@Component
final class CustomerProfileEnrichment extends WorkflowAggregationPart {

    CustomerProfileEnrichment(
            BClient bClient,
            CClient cClient,
            DClient dClient,
            WorkflowExecutor executor) {

        super(AggregationWorkflow.builder("customerProfile")
            .binding("b")
                .source(KeySource.ROOT_SNAPSHOT)
                .items("$.data[*]")
                .keys("basicDetails.customerId")
                .call(keys -> bClient.fetch(keys))
                .responseItems("$.data[*]")
                .responseKeys("customerId")
                .storeAs("bResult")
                .endBinding()

            .binding("c")
                .source(KeySource.ROOT_SNAPSHOT)
                .items("$.data[*]")
                .keys("accounts[*].id")
                .call(keys -> cClient.fetch(keys))
                .responseItems("$.data[*]")
                .responseKeys("accountId")
                .storeAs("cResult")
                .endBinding()

            .binding("d")
                .source(KeySource.STEP_RESULT, "bResult")
                .items("$.data[*]")
                .keys("relatedIds[*].id")
                .call(keys -> dClient.fetch(keys))
                .responseItems("$.data[*]")
                .responseKeys("id")
                .storeAs("dResult")
                .endBinding()

            .compute(new CustomerProfileReducer())
            .write()
                .targetItems("$.data[*]")
                .replaceField("customerProfile")
            .build(), executor);
    }
}
```

The developer of a new enrichment should not manually implement:

```text
- include validation
- dependency expansion
- execution ordering
- same-level concurrency
- meta.parts construction
- downstream error normalization
- RFC 9457 response creation
- patch application to the root document
- common metrics
```

---

## 8. Error and Patch Strategy

### RFC 9457

Use RFC 9457 as the public HTTP error contract.

Public error responses must remain facade-owned problem details:

```text
- type
- title
- status
- detail
- instance
- errorCode
- category
- traceId
- retryable
- timestamp
- optional dependency
- optional violations
```

Do not forward downstream problem documents to clients.

Workflow phases in this plan should map to the existing problem catalog codes rather than introducing new public error categories by wording alone.

Initial mapping rules:

```text
- required enrichment timeout -> ENRICH-TIMEOUT
- required enrichment unavailable -> ENRICH-UNAVAILABLE
- required enrichment unexpected downstream status -> ENRICH-BAD-RESPONSE
- required enrichment invalid payload -> ENRICH-INVALID-PAYLOAD
- required enrichment contract violation -> ENRICH-CONTRACT-VIOLATION
- required enrichment auth failure -> ENRICH-AUTH-FAILED
- patch application / patch conflict / invalid patch -> ORCH-MERGE-FAILED
- workflow invariant violation -> ORCH-INVARIANT-VIOLATED
- workflow mapping / serialization failure -> ORCH-MAPPING-FAILED
- root/main timeout -> MAIN-TIMEOUT
- root/main unavailable -> MAIN-UNAVAILABLE
- root/main unexpected downstream status -> MAIN-BAD-RESPONSE
- root/main invalid payload -> MAIN-INVALID-PAYLOAD
- root/main contract violation -> MAIN-CONTRACT-VIOLATION
- root/main auth failure -> MAIN-AUTH-FAILED
```

### Soft outcome reason rule

For the first workflow phases, reuse the existing public reason `NO_KEYS_IN_MAIN` when keys are missing from `ROOT_SNAPSHOT`, because this preserves the current contract.

For later sources such as `STEP_RESULT` or `TRAVERSAL_STATE`, do not invent public enum values casually. Either:

```text
- map the missing-key condition to an existing stable reason if semantically acceptable; or
- introduce a new public PartSkipReason only through an explicit contract-change phase
  with tests and documentation.
```

Candidate future reasons:

```text
- NO_KEYS_IN_SOURCE
- NO_KEYS_IN_STEP_RESULT
- NO_KEYS_IN_TRAVERSAL_STATE
```

### RFC 6902

Use RFC 6902 only as inspiration for an internal patch model.

Initial supported internal operations:

```text
- add
- replace
- test
```

Do not expose JSON Patch as public API.

Do not initially implement:

```text
- remove
- move
- copy
```

Those can be added later only when real use cases require them.

---

## 9. Non-goals

Do not implement these during this migration unless a later section explicitly asks for them:

```text
- External YAML/JSON workflow DSL
- Full JSONPath engine
- Public PATCH endpoint
- Dynamic runtime loading of enrichments
- Generic scripting engine for compute logic
- Complex graph query language
- Full RFC 6902 operation set
- Replacement of Spring HTTP service clients
- Replacement of the current RFC 9457 error architecture
```

---

# 10. Sequential Migration Phases

Each phase below is intended to be completed as a focused commit or small commit group on the current integration branch, currently `main`.

### Execution protocol

Use this plan as a strict sequential runbook:

```text
- work on the current integration branch, currently `main`
- complete one phase before starting the next phase
- commit phase work directly to `main` only if that remains the chosen workflow
- do not create one PR per phase unless explicitly instructed
- after finishing a phase, update the tracker and add a handoff note
- after finishing a phase, continue with the next phase using the same agreed workflow
- after a larger logical batch, run global verification locally or rely on CI and state that explicitly
- if a phase reveals missing prerequisite work, stop and update the plan instead of silently folding extra architecture into the same commit
```

### Local phase tracker

```text
[x] Phase 1  — Characterization tests
[x] Phase 2  — Internal JSON Patch model
[x] Phase 3  — Patch builder helpers
[x] Phase 4  — Downstream binding model
[x] Phase 5  — Adapt existing keyed support
[x] Phase 6  — Workflow model skeleton
[x] Phase 7  — Keyed binding step
[x] Phase 8  — Migrate account
[x] Phase 9  — Binding metrics and diagnostics
[x] Phase 10 — Migrate owners
[x] Phase 11 — Multi-binding workflow
[x] Phase 12 — Compute step
[x] Phase 13 — Harden patch conflict detection and write ownership
[x] Phase 13.5 — BeneficialOwners legacy contract lock
[x] Phase 14A — Recursive traversal data model
[x] Phase 14B — Recursive traversal engine
[x] Phase 14C — RecursiveFetchStep workflow adapter
[x] Phase 15 — Traversal reducer and write-back bridge
[ ] Phase 16 — Migrate beneficial owners
[ ] Phase 17 — Optional root role abstraction
[ ] Phase 18 — Documentation, test kit, and examples
[ ] Phase 19 — Retire legacy enrichment authoring
```

Every phase must include:

```text
- Tests or explicit focused verification
- Small focused diff
- No unrelated cleanup
- Clear handoff note
- Tracker update when the phase is complete
```

---

## Phase 1 — Characterization Tests

**Status:** Completed.

### Goal

Lock down current behavior before architecture changes.

### Allowed changes

```text
- Test files
- Test fixtures
- Test helper classes
```

Avoid production code changes unless strictly necessary for testability.

### Test scenarios

```text
1. include == null selects all registered parts.
2. include == [] returns root A only.
3. Unknown include fails before calling the main/root downstream.
4. Dependencies are expanded automatically.
5. Same-level parts see the same root snapshot.
6. Same-level results are applied in deterministic graph order.
7. No keys in main produces SKIPPED / NO_KEYS_IN_MAIN.
8. Downstream empty body produces EMPTY / DOWNSTREAM_EMPTY.
9. Downstream 404 produces EMPTY / DOWNSTREAM_NOT_FOUND.
10. Required downstream timeout/auth/5xx produces RFC 9457 error.
11. Optional part failure is recorded as FAILED if current code supports it.
12. Merge failure maps to orchestration failure.
13. Root non-object or empty root body maps to main contract violation.
14. Beneficial owners recursion depth/cycle behavior is documented by tests.
```

### Acceptance criteria

```text
Focused and/or full tests pass.
No public behavior changed.
Tests clearly document current contract.
```

### Forbidden

```text
- No architecture changes
- No production refactoring
- No workflow classes yet
```

---

## Phase 2 — Internal JSON Patch Model

**Status:** Completed.

### Goal

Add an internal explicit patch model beside the existing result model.

### New package

```text
dev.abramenka.aggregation.patch
```

### New classes

```text
JsonPatchDocument
JsonPatchOperation
JsonPatchOperationType
JsonPointer
JsonPatchException
JsonPatchApplicator
```

### Required operation types

```text
add
replace
test
```

### Suggested shape

```java
public record JsonPatchDocument(List<JsonPatchOperation> operations) {
    public boolean isEmpty() {
        return operations.isEmpty();
    }
}

public sealed interface JsonPatchOperation {

    String path();

    record Add(String path, JsonNode value) implements JsonPatchOperation {}

    record Replace(String path, JsonNode value) implements JsonPatchOperation {}

    record Test(String path, JsonNode value) implements JsonPatchOperation {}
}
```

### Modify `AggregationPartResult`

Add a new result type without removing existing ones:

```java
final class JsonPatch implements AggregationPartResult {
    private final String partName;
    private final JsonPatchDocument patch;
}
```

### Modify result application

Extend the current applicator with one new branch:

```java
case AggregationPartResult.JsonPatch patch -> jsonPatchApplicator.apply(patch.patch(), root);
```

### Acceptance criteria

```text
Existing MergePatch and ReplaceDocument behavior unchanged.
Existing tests pass.
New tests for add/replace/test pass.
Patch failure maps to existing orchestration merge failure for now.
```

### Forbidden

```text
- Do not remove MergePatch
- Do not migrate any enrichment yet
- Do not expose JSON Patch in HTTP API
```

---

## Phase 3 — Patch Builder Helpers

**Status:** Completed.

### Goal

Provide a safe builder for patch generation so enrichments do not manually concatenate JSON Pointer strings everywhere.

### New classes

```text
JsonPatchBuilder
JsonPointerBuilder
PatchWriteOptions
```

### Required capabilities

```text
- add object field
- replace object field
- append to array using /-
- test existing value before write
- escape JSON Pointer tokens correctly
```

### Example

```java
JsonPatchDocument patch = JsonPatchBuilder.create()
    .add("/data/0/account1/-", accountNode)
    .replace("/data/0/riskScore", scoreNode)
    .build();
```

### Acceptance criteria

```text
Builder escapes "~" and "/" correctly.
Builder can append to arrays.
Builder does not silently create missing intermediate objects unless explicitly configured.
Patch builder tests pass.
```

### Forbidden

```text
- No business enrichment migration
- No support for remove/move/copy yet
```

---

## Phase 4 — Downstream Binding Model

**Status:** Completed.

### Goal

Introduce a first-class model for one downstream REST dependency inside an enrichment.

This is required because each REST dependency may require different keys from the same source document.

### New package

```text
dev.abramenka.aggregation.workflow.binding
```

### New classes

```text
DownstreamBinding
BindingName
KeySource
KeyExtractionRule
DownstreamCall
ResponseIndexingRule
WriteRule
BindingResult
BindingOutcome
```

### Suggested model

```java
public record DownstreamBinding(
    BindingName name,
    KeyExtractionRule keyExtraction,
    DownstreamCall downstreamCall,
    ResponseIndexingRule responseIndexing,
    @Nullable String storeAs,
    @Nullable WriteRule writeRule
) {}
```

### KeySource

```java
public enum KeySource {
    ROOT_SNAPSHOT,
    CURRENT_ROOT,
    STEP_RESULT,
    TRAVERSAL_STATE
}
```

### Required design rule

```text
A binding owns its own key extraction.

Good:
- binding B extracts keysB from A
- binding C extracts keysC from A
- binding D extracts keysD from B result

Bad:
- part extracts one global key set and shares it across B/C/D
```

```text
A binding may produce a named step result, a patch fragment, or both.

WriteRule is optional.

Binding definition is invalid if it produces neither:
- a stored step result
- nor a patch fragment
```

### Acceptance criteria

```text
Binding model exists.
Invalid binding definitions fail fast.
Unit tests validate binding construction.
No public behavior changed.
```

### Forbidden

```text
- Do not use the binding model in production flow yet
- Do not migrate existing enrichments yet
```

---

## Phase 5 — Adapt Existing Keyed Support

**Status:** Completed.

### Goal

Reuse existing path-based keyed enrichment mechanics inside the new binding model.

Current useful concepts should be preserved:

```text
- EnrichmentRule
- ItemKeyExtractor
- PathExpression
- KeyPathGroups
```

### Required changes

```text
1. Keep existing EnrichmentRule-based code working.
2. Add adapter from EnrichmentRule to DownstreamBinding where useful.
3. Extract reusable services:
   - KeyExtractor
   - ResponseIndexer
   - TargetMatcher
4. Make source explicit, starting with ROOT_SNAPSHOT.
```

### Adapter clarification

An automatic `EnrichmentRule` -> `DownstreamBinding` adapter is useful only if it does not require reconstructing data that `EnrichmentRule` no longer exposes.

If the existing `EnrichmentRule` stores parsed functions and discards raw path strings, do not add a fake adapter that requires callers to pass the same path strings again. In that case, write new bindings directly in Phase 8 and keep the shared parser/services as the real reuse point.

### Path dialect constraint

```text
Keep workflow bindings on the same limited path dialect already supported by PathExpression.
Do not expand this phase into a JSONPath implementation project.
```

### Acceptance criteria

```text
Existing keyed enrichments still work.
New binding tests reuse existing path/key extraction logic.
No duplicate key extraction implementation.
No full JSONPath dependency added.
```

### Forbidden

```text
- Do not remove EnrichmentRule yet
- Do not change path syntax broadly
- Do not add filter/index/slice JSONPath semantics
```

---

## Phase 6 — Workflow Model Skeleton

**Status:** Completed with explicit scope limits.

### Goal

Introduce workflow as an implementation style for `AggregationPart`.

A workflow-based part must be just another `AggregationPart` bean. The existing planner and part executor must not be rewritten for this phase.

### Completed scope

New package:

```text
dev.abramenka.aggregation.workflow
```

New classes:

```text
AggregationWorkflow
WorkflowAggregationPart
WorkflowExecutor
WorkflowContext
WorkflowResult
WorkflowVariableStore
WorkflowStep
StepResult
WorkflowDefinitionValidator
```

### Implemented behavior

```text
- WorkflowAggregationPart adapts AggregationWorkflow to the existing AggregationPart contract.
- name(), dependencies(), and criticality() are exposed from AggregationWorkflow.
- execute(...) delegates to WorkflowExecutor.
- WorkflowExecutor runs steps sequentially.
- Applied step patches are accumulated into one JsonPatchDocument.
- Stored step values are written into WorkflowVariableStore.
- First SKIPPED or EMPTY step short-circuits the workflow.
- WorkflowResult maps back to AggregationPartResult.
- Duplicate step names and blank step names fail during workflow validation.
```

### Important limits after Phase 6

```text
- No existing enrichment is migrated yet.
- No concrete binding execution exists yet.
- No CURRENT_ROOT working document exists yet.
- No recursive traversal exists yet.
- No compute framework exists yet.
- No binding metrics exist yet.
- Validation is skeleton-level only.
```

### Acceptance criteria

```text
A dummy WorkflowAggregationPart can be constructed.
Planner can discover a workflow part because it is still an AggregationPart bean.
WorkflowExecutor can execute dummy steps.
Existing parts are unaffected.
Duplicate step names fail fast.
Blank step names fail fast.
Existing test suite passes.
```

### Handoff note for Phase 7

Phase 7 must not reinterpret the skeleton as a complete workflow engine.

The next phase should add exactly one production-useful workflow step:

```text
KeyedBindingStep
```

Do not migrate `account` in the same phase as Phase 7.

---

## Phase 7 — Keyed Binding Step

**Status:** Completed.

### Handoff note

- **Completed:** Phase 7 — Keyed binding step
- **Files added:** `workflow/step/KeyedBindingStep.java`, `workflow/step/package-info.java`
- **Files modified:** `enrichment/support/keyed/PathExpression.java` (added `toItemPointerAt(int)`), `WorkflowExecutorTest.java` (2 integration tests added)
- **Files added (test):** `workflow/step/KeyedBindingStepTest.java` (14 scenarios)
- **Intentionally not done:** no existing enrichment migrated; CURRENT_ROOT/STEP_RESULT/TRAVERSAL_STATE sources rejected at construction; multi-binding not implemented. AppendToArray auto-creation was finalized later during Phase 8 to match legacy keyed-enrichment behavior.
- **Local checks run:** `./gradlew test --tests '*KeyedBindingStepTest' --tests '*WorkflowExecutorTest'` — green; `spotlessJavaCheck` — green
- **CI:** full suite deferred to CI per plan section 2
- **Next phase:** Phase 8 — migrate `account` enrichment to `WorkflowAggregationPart` using one `KeyedBindingStep`

### Goal

Implement the simple keyed enrichment pattern using the binding model.

Pattern:

```text
ROOT_SNAPSHOT
  -> endpoint-specific keys
  -> REST downstream call
  -> response indexing
  -> target matching
  -> JsonPatchDocument
```

This phase must make the workflow skeleton production-usable for a single simple keyed binding, but it must not migrate any existing business enrichment yet.

### New class

Suggested package:

```text
dev.abramenka.aggregation.workflow.step
```

Suggested class:

```text
KeyedBindingStep
```

### Required dependencies

`KeyedBindingStep` should reuse the existing Phase 4/5 abstractions:

```text
- DownstreamBinding
- KeyExtractionRule
- KeySource
- DownstreamCall
- ResponseIndexingRule
- WriteRule
- KeyExtractor
- ResponseIndexer
- TargetMatcher
- JsonPatchBuilder
```

Do not duplicate path parsing or key extraction logic.

### Supported source in this phase

Phase 7 supports only:

```text
KeySource.ROOT_SNAPSHOT
```

The following sources must fail explicitly if used:

```text
KeySource.CURRENT_ROOT
KeySource.STEP_RESULT
KeySource.TRAVERSAL_STATE
```

Use `UnsupportedOperationException`, `IllegalArgumentException`, or a workflow validation exception consistently, but do not silently ignore unsupported sources.

### Execution algorithm

`KeyedBindingStep` must perform the following steps:

```text
1. Read source document from WorkflowContext.rootSnapshot().
2. Extract targets and keys using KeyExtractor.
3. Deduplicate request keys while preserving first-seen order.
4. If no keys are found, return SKIPPED / NO_KEYS_IN_MAIN.
5. Call DownstreamCall with deduplicated keys.
6. Handle soft downstream outcomes consistently with existing enrichment behavior.
7. Index downstream response using ResponseIndexer.
8. Match extracted targets to indexed response entries using TargetMatcher.
9. Convert matches into JsonPatchDocument using WriteRule and JsonPatchBuilder.
10. Return StepResult.applied(patch) only when the patch contains real writes.
11. If the binding has storeAs, store the downstream response or indexed result consistently.
```

### Soft outcome rules

```text
No keys from ROOT_SNAPSHOT:
  SKIPPED / NO_KEYS_IN_MAIN

Downstream empty body:
  EMPTY / DOWNSTREAM_EMPTY

Downstream 404:
  EMPTY / DOWNSTREAM_NOT_FOUND
```

For this phase, do not invent new public `PartSkipReason` values.

For non-root sources, reject the source because Phase 7 does not support them yet.

### Fatal outcome rules

Fatal downstream exceptions must continue to flow through the existing part failure policy and RFC 9457 facade error handling.

Expected mappings:

```text
Auth failure:
  ENRICH-AUTH-FAILED

Timeout:
  ENRICH-TIMEOUT

Unavailable:
  ENRICH-UNAVAILABLE

Unexpected downstream status:
  ENRICH-BAD-RESPONSE

Invalid downstream payload:
  ENRICH-INVALID-PAYLOAD or ENRICH-CONTRACT-VIOLATION

Patch generation/application failure:
  ORCH-MERGE-FAILED

Workflow invariant violation:
  ORCH-INVARIANT-VIOLATED

Mapping/serialization failure:
  ORCH-MAPPING-FAILED
```

Do not forward downstream problem documents to clients.

### Write semantics

Initial supported write actions:

```text
- ReplaceField
- AppendToArray
```

Rules:

```text
ReplaceField:
  writes the matched downstream response entry into the named field of the matched target item.

AppendToArray:
  appends the matched downstream response entry to the named array field of the matched target item.

If AppendToArray target field does not exist:
  Implemented behavior after Phase 8 is to auto-create an empty array before the first append.
  This matches legacy keyed-enrichment behavior based on withArrayProperty-style output.
  The behavior must remain explicit and tested.

If AppendToArray target field exists but is not an array:
  Fail fast with an orchestration merge failure.

If one owner item matches multiple response entries:
  Emit the array-create operation only once for that owner item, then append all matched entries.

If ReplaceField target field does not exist:
  Use add deliberately.

If ReplaceField target field already exists:
  Use replace deliberately.
```

The chosen behavior must be tested.

### Empty match behavior

If keys were extracted and downstream returned a valid non-empty response, but no target matched the indexed response:

```text
Do not silently return APPLIED with an empty patch.
```

Choose one explicit behavior and document it in tests.

Recommended initial behavior:

```text
EMPTY / DOWNSTREAM_EMPTY
```

Alternative acceptable behavior if the existing enrichment contract expects strict consistency:

```text
ENRICH-CONTRACT-VIOLATION
```

Do not add a new public reason in Phase 7.

### Validation

`KeyedBindingStep` construction should fail fast for invalid definitions.

Validation should cover:

```text
- blank step name
- null DownstreamBinding
- unsupported KeySource
- missing WriteRule when the step is expected to write
- invalid/blank target item path
- invalid/blank action field name
- invalid path syntax where PathExpression can detect it
```

Do not rely only on request-time failures.

### Tests required

Unit tests:

```text
KeyedBindingStepTest
```

Required scenarios:

```text
1. no keys from ROOT_SNAPSHOT -> SKIPPED / NO_KEYS_IN_MAIN
2. extracted duplicate keys are deduplicated before downstream call
3. downstream response is indexed with fallback response key paths
4. matched targets produce JsonPatchDocument writes
5. unmatched targets are ignored or handled by explicit tested rule
6. empty downstream response -> EMPTY / DOWNSTREAM_EMPTY
7. downstream 404 -> EMPTY / DOWNSTREAM_NOT_FOUND
8. unsupported KeySource fails explicitly
9. invalid binding definition fails fast
10. patch contains no accidental writes when no matches exist
```

Integration-style test:

```text
WorkflowExecutor + KeyedBindingStep
```

Required scenarios:

```text
1. workflow with one KeyedBindingStep produces AggregationPartResult.JsonPatch
2. soft outcome from KeyedBindingStep maps to AggregationPartResult.NoOp
3. existing meta.parts behavior remains owned by AggregationPartExecutor
```

### Acceptance criteria

```text
Can express account/owners-style simple keyed enrichment through one binding.
No manual merge code is required in a new workflow part.
No existing enrichment is migrated yet.
No public response shape changes.
No error contract changes.
No meta.parts changes.
Focused tests pass locally.
CI is allowed to run the broader suite.
```

### Forbidden

```text
- Do not migrate account in Phase 7.
- Do not migrate owners in Phase 7.
- Do not migrate beneficialOwners in Phase 7.
- Do not implement multi-binding workflow.
- Do not implement compute steps.
- Do not implement recursive traversal.
- Do not introduce a full JSONPath engine.
- Do not add binding metrics yet.
- Do not introduce public PATCH API.
```

---

## Phase 8 — Migrate One Simple Enrichment

**Status:** Completed.

### Handoff note

- **Completed:** Phase 8 — migrate account enrichment to WorkflowAggregationPart
- **Files changed (production):** `DownstreamCall.java` (added `AggregationContext context` param), `KeyedBindingStep.java` (pass context to fetch; AppendToArray auto-creates absent array with per-owner dedup), `AccountEnrichment.java` (rewritten to extend WorkflowAggregationPart)
- **Files changed (test):** `KeyedBindingStepTest.java` (lambdas updated; AppendToArray tests extended), `WorkflowExecutorTest.java`, `DownstreamBindingTest.java`, `AccountEnrichmentTestFactory.java`, `AggregateServiceTestSupport.java`, `AggregateServiceDependencyTest.java`
- **Phase 7 extension:** AppendToArray now auto-creates the array field if absent (matches legacy `withArrayProperty` behavior); existing-non-array field still fails fast; per-owner identity tracking prevents double-create for multiple matches on the same item
- **Intentionally not done:** owners not migrated; beneficialOwners not migrated; ObjectMapper dependency removed from account (uses JsonNodeFactory directly)
- **Local checks run:** focused account + workflow tests green; `./gradlew test` green; `spotlessJavaCheck verifyBoot4Classpath jacocoTestCoverageVerification` green
- **Next phase:** Phase 9 — binding metrics and diagnostics

### Additional precondition

Before starting Phase 8, Phase 7 must be present in the current integration branch and validated by focused tests and/or CI.

Phase 8 must start by re-reading:

```text
- Account enrichment implementation
- Account characterization tests
- KeyedBindingStep tests
- AGGREGATION_WORKFLOW_MIGRATION_PLAN.md
```

Do not start Phase 8 if Phase 7 left unresolved ambiguity around empty matches or write semantics.

### Goal

Prove the workflow model by migrating one existing simple enrichment.

Preferred first candidate:

```text
account
```

Choose the simplest keyed enrichment first.

### Migration rule

Before:

```text
Account part is implemented through the current shared keyed-enrichment flow
or equivalent hand-written AggregationPart implementation.
```

After:

```text
Account part extends WorkflowAggregationPart.
Account part declares one DownstreamBinding.
Account part uses one KeyedBindingStep.
Workflow engine handles extraction/fetch/index/write.
```

### Acceptance criteria

```text
All old account tests pass unchanged.
No public response shape change.
No error contract change.
No meta.parts change.
Manual execute/merge code removed only for account.
Other parts untouched.
```

### Forbidden

```text
- Do not migrate owners in the same phase.
- Do not migrate beneficialOwners in the same phase.
- Do not introduce recursive traversal.
- Do not introduce compute framework.
- Do not introduce binding metrics unless Phase 9 is intentionally done first.
```

---

## Phase 9 — Binding Metrics and Diagnostics

**Status:** Completed.

### Handoff note

- **Completed:** Phase 9 — binding metrics and diagnostics
- **Files added:** `WorkflowBindingMetrics.java` (new `@Component`)
- **Files modified (production):** `WorkflowStep.java` (added default `bindingName()`), `KeyedBindingStep.java` (overrides `bindingName()` → binding.name().value()), `WorkflowExecutor.java` (injects WorkflowBindingMetrics, records per step)
- **Files modified (test):** `WorkflowExecutorTest.java` (5 new binding metrics scenarios), `WorkflowAggregationPartTest.java` (executor wiring), `AccountEnrichmentTestFactory.java` (executor wiring + `noopWorkflowExecutor()` helper)
- **Metric:** `aggregation.binding.requests{part, binding, outcome}` — outcomes: success/empty/skipped/failed
- **Binding tag:** `KeyedBindingStep.bindingName()` exposes `DownstreamBinding.name()` so the tag shows the downstream identity (`"account"`) rather than the generic step name (`"fetch"`)
- **Intentionally not done:** no logging added (safe field logging not required by the plan for this phase), no binding details in public bodies
- **Local checks run:** focused workflow + account tests green; spotlessApply clean
- **CI:** full suite deferred to Phase 10 checkpoint per plan section 2
- **Next phase:** Phase 10 — migrate owners enrichment to workflow model

### Goal

Keep public `meta.parts` at business-part level, but add internal binding-level observability.

### New metric

```text
aggregation.binding.requests
```

Suggested tags:

```text
part
binding
outcome
```

Suggested outcomes:

```text
success
empty
skipped
failed
```

### Logging rule

Binding names may appear in logs.

Do not expose:

```text
- downstream hostnames
- downstream URLs
- raw downstream bodies
- tokens
- internal stack traces
```

### Acceptance criteria

```text
Existing part metrics unchanged.
New binding metrics emitted for workflow parts.
No binding details leaked to public error bodies.
Focused metrics tests pass locally.
CI is allowed to run the broader suite.
```

### Forbidden

```text
- Do not change public meta.parts shape.
- Do not expose binding-level status in public response bodies.
- Do not include raw payloads or secrets in logs.
```

---

## Phase 10 — Migrate Second Simple Enrichment

**Status:** Completed.

### Handoff note

- **Completed:** Phase 10 — migrate owners enrichment to WorkflowAggregationPart
- **Files changed (production):** `OwnersEnrichment.java` (rewritten — extends WorkflowAggregationPart; KeyExtractionRule with two fallback key paths; ResponseIndexingRule with two fallback response key paths; AppendToArray to owners1)
- **Files changed (test):** `OwnersEnrichmentTestFactory.java` (removed objectMapper, reuses `AccountEnrichmentTestFactory.noopWorkflowExecutor()`), `AggregateServiceTestSupport.java` (updated call site), `AggregateServiceDependencyTest.java` (updated call site)
- **Files added (test):** `OwnersEnrichmentWorkflowTest.java` (9 focused scenarios: fallback source key paths, fallback response key paths, owners1 write, auto-create, soft outcomes, binding metric)
- **Exact legacy behavior preserved:** two-fallback key extraction, two-fallback response indexing, AppendToArray owners1, optional-body downstream, @Order(LOWEST_PRECEDENCE - 1)
- **WriteRule.MatchBy note:** uses primary paths (basicDetails.owners[*].id / individual.number) for documentation; algorithmic matching is through TargetMatcher via ExtractedTarget.key as before
- **Intentionally not done:** beneficialOwners not migrated; multi-binding not introduced; objectMapper dependency removed (uses JsonNodeFactory directly)
- **Local checks run:** focused owners + account + workflow tests green; `./gradlew test` green; spotlessJavaCheck verifyBoot4Classpath jacocoTestCoverageVerification green — Phase 9+10 checkpoint complete locally
- **Next phase:** Phase 11 — multi-binding workflow

### Preconditions

Phase 10 was completed from the actual `main` implementation after Phases 7 through 9.

Required current state:

```text
- Phase 7 is completed: KeyedBindingStep exists and supports one keyed binding from ROOT_SNAPSHOT.
- Phase 8 is completed: account is already migrated to WorkflowAggregationPart.
- Phase 9 is completed: aggregation.binding.requests metrics exist for workflow bindings.
- owners is still legacy-based and has not been migrated yet.
- beneficialOwners is still out of scope for this phase.
```

Before editing, verify the current implementation rather than relying only on this summary.
Use `fff`/`rg` to inspect at least:

```text
OwnersEnrichment
AccountEnrichment
KeyedBindingStep
DownstreamBinding
KeyExtractionRule
ResponseIndexingRule
WriteRule
WorkflowExecutor
WorkflowBindingMetrics
```

### Goal

Migrate the second simple keyed enrichment to the workflow model.

Target enrichment:

```text
owners
```

This phase validates the already-implemented single-binding workflow support with a slightly richer legacy rule:

```text
- multiple source key paths
- fallback source keys
- fallback response keys
- append-to-array target field
- binding-level metrics for a second workflow part
```

### Current owners legacy rule

The current legacy owners enrichment is represented by this behavior and must be preserved exactly:

```text
Business part name:
  owners

Source/root item path:
  $.data[*]

Source key paths, in fallback/declaration order:
  basicDetails.owners[*].id
  basicDetails.owners[*].number

Request keys field:
  ids

Downstream client call:
  Owners.fetchOwners(request, Owners.DEFAULT_FIELDS, context.clientRequestContext())

Response item path:
  $.data[*]

Response key paths, in fallback/declaration order:
  individual.number
  id

Write action:
  AppendToArray("owners1")

Target field:
  owners1
```

Phase 10 should migrate this behavior, not reinterpret it.

### Expected migration result

Before:

```text
OwnersEnrichment extends the legacy KeyedArrayEnrichment path and declares an EnrichmentRule.
```

After:

```text
OwnersEnrichment extends WorkflowAggregationPart.
OwnersEnrichment declares one AggregationWorkflow.
OwnersEnrichment declares one DownstreamBinding.
OwnersEnrichment uses one KeyedBindingStep.
Workflow infrastructure performs key extraction, downstream fetch, response indexing, target matching, patch generation, and binding metrics.
```

Expected binding shape:

```text
DownstreamBinding name:
  owners

KeyExtractionRule:
  source = ROOT_SNAPSHOT
  sourceItemPath = $.data[*]
  keyPaths = [basicDetails.owners[*].id, basicDetails.owners[*].number]

ResponseIndexingRule:
  responseItemPath = $.data[*]
  responseKeyPaths = [individual.number, id]

WriteRule:
  targetItemPath = $.data[*]
  matchBy = existing keyed match fields required by KeyedBindingStep
  action = AppendToArray("owners1")

DownstreamCall:
  build request object with field ids
  call Owners.fetchOwners(request, Owners.DEFAULT_FIELDS, context.clientRequestContext())
  wrap optional body through the existing DownstreamClientResponses.optionalBody semantics
```

### Phase 10 must not become Phase 11

The current workflow infrastructure already supports the pieces Phase 10 needs:

```text
- multiple source key paths through KeyExtractionRule / KeyExtractor
- fallback response key paths through ResponseIndexingRule / ResponseIndexer
- append-to-array writes through KeyedBindingStep
- absent-array auto-create behavior through KeyedBindingStep
- workflow binding metrics through WorkflowExecutor + WorkflowBindingMetrics
```

Therefore Phase 10 should be a migration phase, not an architecture-expansion phase.

If owners cannot be migrated with the current single-binding model, stop and report the exact missing capability instead of implementing Phase 11 inside Phase 10.

### WriteRule.MatchBy guardrail

Do not redesign `WriteRule.MatchBy` in Phase 10 unless focused owners tests prove it is required.

For Phase 10, prefer using the existing fallback paths in:

```text
- KeyExtractionRule.keyPaths()
- ResponseIndexingRule.responseKeyPaths()
```

`WriteRule.MatchBy` currently carries the keyed matching fields required by `KeyedBindingStep`; broader list-based or multi-source matching belongs to a later phase only if the implementation proves it is necessary.

If `WriteRule.MatchBy` becomes insufficient for owners, stop and report before changing the model.

### Required implementation steps

```text
1. Re-read the legacy OwnersEnrichment behavior.
2. Re-read AccountEnrichment as the working workflow-based example.
3. Re-read KeyedBindingStep tests that cover fallback source/response keys and AppendToArray behavior.
4. Convert OwnersEnrichment from the legacy keyed-enrichment base class to WorkflowAggregationPart.
5. Declare one owners DownstreamBinding with the exact legacy paths and target field.
6. Declare one KeyedBindingStep in the owners workflow.
7. Preserve the existing downstream call semantics and optional-body handling.
8. Remove only the owners-specific manual/legacy keyed join path.
9. Keep account unchanged.
10. Keep beneficialOwners unchanged.
```

### Tests required

Add or update focused tests so they prove at least:

```text
1. owners uses fallback source key path basicDetails.owners[*].id.
2. owners uses fallback source key path basicDetails.owners[*].number.
3. owners indexes downstream response by individual.number.
4. owners indexes downstream response by id when individual.number is absent.
5. owners writes to owners1 with the same response shape as before.
6. owners preserves absent-array auto-create behavior if the legacy behavior expected it.
7. owners keeps existing soft outcomes unchanged: no keys, empty downstream, 404 downstream.
8. account workflow behavior is not changed by the owners migration.
9. binding metric aggregation.binding.requests is emitted for owners with binding=owners.
```

Prefer preserving existing owners characterization tests. Only update test wiring if the implementation moved from legacy base class to workflow part.

### Acceptance criteria

```text
Owners behavior unchanged.
Owners response shape unchanged.
Owners error behavior unchanged.
Success-side meta.parts unchanged.
Fallback key extraction works.
Fallback response indexing works.
No manual keyed join logic remains in owners part.
Existing owners tests pass.
Existing account workflow tests pass.
Binding metrics still work for account and owners.
No Phase 11 multi-binding semantics are introduced.
```

### Verification

Run focused checks first:

```bash
./gradlew test --tests '*Owners*'
./gradlew test --tests '*Account*'
./gradlew test --tests '*KeyedBindingStepTest'
./gradlew test --tests '*WorkflowExecutorTest'
```

Because Phase 9 and Phase 10 are a recommended checkpoint, run or explicitly defer broader verification:

```bash
./gradlew test
./gradlew spotlessJavaCheck verifyBoot4Classpath jacocoTestCoverageVerification
./gradlew build
```

If broader verification is skipped locally because CI is expected to cover it, record that explicitly in the Phase 10 handoff note.

### Handoff note template for Phase 10

After completing Phase 10, update this section with a handoff note containing:

```text
- Completed: Phase 10 — migrate owners enrichment to WorkflowAggregationPart
- Files changed in production
- Files changed in tests
- Exact legacy behavior preserved
- What was intentionally not done
- Local checks run
- CI/full verification status
- Next phase: Phase 11 — Multi-binding workflow
```

### Stop and report instead of guessing if

```text
- owners cannot be represented by one DownstreamBinding and one KeyedBindingStep
- owners requires true multi-binding workflow
- owners requires CURRENT_ROOT or STEP_RESULT
- fallback source key extraction does not behave like the legacy EnrichmentRule
- fallback response indexing does not behave like the legacy EnrichmentRule
- existing owners tests reveal a public contract difference
- changing WriteRule.MatchBy appears necessary
- current code contradicts this plan
```

### Forbidden

```text
- Do not migrate beneficialOwners yet.
- Do not migrate any other enrichment.
- Do not introduce recursive traversal here.
- Do not introduce compute steps here.
- Do not implement multi-binding workflow here.
- Do not implement CURRENT_ROOT or STEP_RESULT here.
- Do not redesign WriteRule.MatchBy unless tests prove owners cannot work without it and the plan is updated first.
- Do not change the public REST API.
- Do not change the public response JSON contract.
- Do not change RFC 9457 ProblemDetail behavior.
- Do not change success-side meta.parts behavior.
```

---

## Phase 11 — Multi-binding Workflow

**Status:** Completed.

### Handoff note

- **Completed:** Phase 11 — multi-binding workflow
- **Files added (production):** `WorkflowPatchConflictDetector.java` (internal, per-execution conflict tracker)
- **Files modified (production):** `WorkflowContext.java` (deep-copies rootSnapshot; adds mutable `currentRoot`), `KeyExtractor.java` (source guard removed — caller is responsible for source resolution), `KeyedBindingStep.java` (supports ROOT_SNAPSHOT/CURRENT_ROOT/STEP_RESULT; rejects TRAVERSAL_STATE and STEP_RESULT+writeRule), `WorkflowExecutor.java` (applies each step patch to `currentRoot`; conflict-checks via `WorkflowPatchConflictDetector`; wraps `JsonPatchException` as `ORCH-MERGE-FAILED`)
- **Files added (test):** `WorkflowPatchConflictDetectorTest.java` (8 unit scenarios)
- **Files modified (test):** `WorkflowExecutorTest.java` (11 multi-binding scenarios), `KeyedBindingStepTest.java` (CURRENT_ROOT/STEP_RESULT tests inverted to positive; TRAVERSAL_STATE + STEP_RESULT+writeRule stay negative), `KeyExtractorTest.java` (negative source test replaced with positive)
- **Phase 11 limitation noted:** STEP_RESULT + writeRule rejected at construction (write targets in step-result docs don't map to global root paths; deferred to Phase 13 write ownership)
- **combinesAppliedPatchesInOrder test:** updated to use different paths (/a and /b) since the conflict detector correctly rejects same-path different-op across steps
- **Intentionally not done:** parallel execution, complex scheduler, TRAVERSAL_STATE, Phase 12 compute, Phase 13 ownership
- **Local checks run:** focused workflow + account + owners tests green; `./gradlew test` green; `spotlessApply` clean; Phase 11–13 batch verification deferred to CI per plan section 2
- **Next phase:** Phase 12 — compute step

### Goal

Allow one business part to contain multiple REST bindings.

Pattern:

```text
One business enrichment
  -> REST B using keysB from A
  -> REST C using keysC from A
  -> REST D using keysD from A or previous result
  -> produce one combined patch
```

### Required changes

```text
1. WorkflowContext stores named step/binding results.
2. Binding can read keys from:
   - ROOT_SNAPSHOT
   - CURRENT_ROOT
   - STEP_RESULT(bindingName)
3. WorkflowExecutor runs steps sequentially by default.
4. Independent bindings may be optimized later; do not implement parallel optimization now.
```

### CURRENT_ROOT requirement

Phase 11 is the first phase that must support real `CURRENT_ROOT` semantics.

Before Phase 11 is accepted, the workflow executor must be able to maintain a workflow-local working document:

```text
CURRENT_ROOT = ROOT_SNAPSHOT + patches produced by previous workflow steps of the same business part
```

Rules:

```text
- CURRENT_ROOT is local to one workflow execution.
- CURRENT_ROOT is not the global root owned by AggregationPartExecutor.
- Patches may be applied to CURRENT_ROOT between workflow steps.
- The final AggregationPartResult must still contain the combined patch.
- The final patch must still be applied to the global root once by AggregationPartExecutor.
```

### Minimal safety contract for this phase

This phase implements only minimal local conflict rejection required for safe multi-binding execution.

Full write ownership and broader conflict validation are handled later in Phase 13.

Before the later dedicated patch-conflict phase, the first multi-binding implementation must already reject these cases deterministically:

```text
1. same final path written twice with different values
2. add and replace on the same final path
3. write into a missing parent path
4. failed test operation
```

Initial mapping for these failures remains:

```text
ORCH-MERGE-FAILED
```

### Suggested source reference

```java
sealed interface KeySourceRef {
    record RootSnapshot() implements KeySourceRef {}
    record CurrentRoot() implements KeySourceRef {}
    record StepResult(String stepName) implements KeySourceRef {}
}
```

### Acceptance criteria

```text
A workflow with two bindings can run.
A later binding can extract keys from an earlier binding result.
A later binding can extract keys from CURRENT_ROOT.
Independent bindings are allowed but not necessarily parallel.
Final patch combines writes from multiple bindings.
Patch operations are applied once at part end to the global root.
Bindings may be fetch-only via storeAs(...) without forcing immediate global root writes.
```

### Forbidden

```text
- No recursive traversal yet.
- No complex scheduler.
- No automatic parallel dependency planner inside workflow yet.
```

---

## Phase 12 — Compute Step

**Status:** Completed.

### Handoff note

- **Completed:** Phase 12 — compute step
- **Files added (production):** `workflow/compute/WorkflowComputation.java` (interface), `workflow/compute/WorkflowValues.java` (read-only input view), `workflow/compute/ComputationInput.java` (input declaration record), `workflow/compute/ComputationResult.java` (output wrapper record), `workflow/compute/ComputationException.java` (input-error vs invariant flag), `workflow/compute/package-info.java`, `workflow/step/ComputeStep.java` (WorkflowStep implementation)
- **No existing production files changed** — ComputeStep integrates via StepResult.stored() which the executor already handles
- **Files added (test):** `workflow/step/ComputeStepTest.java` (16 scenarios)
- **Error mapping:** ComputationException.inputViolation() → ENRICH-CONTRACT-VIOLATION; all other exceptions → ORCH-INVARIANT-VIOLATED
- **Intentionally not done:** no downstream calls in compute, no patch application, no recursive traversal, no Phase 13 ownership model; TRAVERSAL_STATE rejected in ComputationInput at construction
- **Local checks run:** ComputeStepTest + Account + Owners + WorkflowExecutorTest all green; `./gradlew test spotlessJavaCheck verifyBoot4Classpath jacocoTestCoverageVerification` green
- **Next phase:** Phase 13 — harden patch conflict detection and write ownership

### Goal

Support computed enrichment values.

Pattern:

```text
A -> keys -> REST D/E -> compute number/value -> write into A
```

### New abstractions

```text
ComputeStep
WorkflowComputation
ComputationInput
ComputationResult
```

### Interface option

Start synchronous unless reactive computation is genuinely needed:

```java
public interface WorkflowComputation {
    JsonNode compute(WorkflowValues values);
}
```

### Rule

A computation class contains business logic only.

It must not:

```text
- call downstreams
- mutate root JSON directly
- apply patches
- build HTTP errors
```

### Acceptance criteria

```text
Can fetch D and E, compute scalar, write scalar into A.
Computation errors map deterministically.
No downstream logic inside computation class.
Tests cover happy path and compute failure.
```

### Error mapping guidance

```text
Bad/missing input data from downstream:
  ENRICH-CONTRACT-VIOLATION if the downstream payload violates expected contract

Bug in computation code:
  ORCH-INVARIANT-VIOLATED or ORCH-MAPPING-FAILED
```

---

## Phase 13 — Harden Patch Conflict Detection and Write Ownership

**Status:** Completed.

### Handoff note

- **Completed:** Phase 13 — harden patch conflict detection and write ownership
- **Files added (production):** `workflow/ownership/OwnedTarget.java`, `workflow/ownership/WriteOwnership.java`, `workflow/ownership/package-info.java`
- **Files modified (production):** `AggregationWorkflow.java` (optional 5th `@Nullable WriteOwnership` field; backward-compatible 4-arg constructor delegates to 5-arg), `WorkflowStep.java` (added `default writtenFieldName()`), `KeyedBindingStep.java` (overrides `writtenFieldName()`), `WorkflowDefinitionValidator.java` (ownership validation when declared), `AccountEnrichment.java` (`WriteOwnership.of("account1")`), `OwnersEnrichment.java` (`WriteOwnership.of("owners1")`)
- **Files added (test):** `WriteOwnershipValidationTest.java` (12 scenarios)
- **Files modified (test):** `WorkflowExecutorTest.java` (4 new Phase 13 scenarios: idempotent write, array append, no-partial-patch on global root ×2, ORCH-MERGE-FAILED mapping)
- **Phase 11 conflict detection unchanged** — already covered rules 1-5; Phase 13 adds static ownership on top
- **No partial global patch**: `accumulated.addAll()` only runs after both conflict-check and applicator succeed; workflow failure prevents AggregationPartExecutor from touching the global root — proven by two new tests
- **Error mapping**: conflicts stay ORCH-MERGE-FAILED; no new catalog entries needed
- **Intentionally not done:** ORCH-PATCH-CONFLICT/ORCH-PATCH-INVALID not introduced (no product contract requires distinction); beneficialOwners not migrated; recursive traversal not introduced; no public API, response JSON, RFC 9457, or success-side `meta.parts` contract changes
- **Local checks run:** focused + full suite green; Phase 11–13 checkpoint: `./gradlew test spotlessJavaCheck verifyBoot4Classpath jacocoTestCoverageVerification` green
- **Post-Phase-13 safety update:** `AccountEnrichment` now declares ownership for `account1`; `OwnersEnrichment` now declares ownership for `owners1`. This enables the Phase 13 static ownership guardrails on real migrated workflow parts without changing runtime response behavior.
- **Next phase:** Phase 14 — recursive traversal skeleton

### Goal

Detect unsafe writes before applying them.

### New abstractions

```text
WriteOwnership
OwnedTarget
```

### Ownership rule

```text
Each workflow part should declare a coarse-grained intended write-set.

The declaration is used for:
- reviewability
- static validation
- conflict checks
- docs and tests

Keep the first version coarse-grained and field-oriented.
Do not turn write ownership into a second path DSL.
```

### Initial conflict rules

```text
1. Same path replaced twice with different values -> conflict.
2. add and replace on the same path -> conflict.
3. failed test operation -> invalid patch.
4. write into missing parent -> invalid patch.
5. array append using /- is allowed.
```

### Error mapping

Initial acceptable mapping:

```text
ORCH-MERGE-FAILED
```

Optional later explicit codes:

```text
ORCH-PATCH-CONFLICT
ORCH-PATCH-INVALID
```

Add explicit catalog entries only if tests and product contract need stable distinction.

### Acceptance criteria

```text
Conflicting patch fails deterministically.
No partial patch is applied after failure.
Failure returns RFC 9457 problem response.
Tests cover conflicts and failed test operations.
Declared write ownership is checked against obviously conflicting workflow definitions.
```

---

## Phase 13.5 — BeneficialOwners Legacy Contract Lock

**Status:** Completed.

### Goal

Lock the current `beneficialOwners` behavior before introducing reusable recursive traversal infrastructure.

This is a contract-first phase. It is intentionally placed between Phase 13 and Phase 14 because the remaining migration is risky: Phase 14 should model the current recursive behavior, not invent a generic graph framework that later fails to preserve the legacy contract.

### Allowed changes

```text
- AGGREGATION_WORKFLOW_MIGRATION_PLAN.md
- BeneficialOwners characterization tests if existing tests do not already lock the contract
- Test fixtures needed by those characterization tests
```

### Forbidden

```text
- No production code changes unless a characterization test requires a tiny testability-only adjustment.
- No workflow.recursive package yet.
- No RecursiveFetchStep yet.
- No reducer yet.
- No BeneficialOwnersEnrichment migration.
- No change to public response shape.
- No change to RFC 9457 problem response shape.
- No change to meta.parts behavior.
```

### Code to inspect first

```text
BeneficialOwnersEnrichment
OwnershipResolver
EntityNumbersExtractor
RootEntityTargets
BeneficialOwnersDetailsPayload
BeneficialOwnersResolutionException
RootEntityTarget
ResolvedEntity
Existing beneficialOwners tests and fixtures
```

### Legacy contract to lock

The following behavior must be treated as authoritative for Phases 14 through 16.

```text
Business part name:
  beneficialOwners

Dependency:
  dependsOn owners

Skip behavior:
  If no root entity owners exist under owners1, return SKIPPED / NO_KEYS_IN_MAIN.

Root entity target selection:
  Iterate root.data[*].owners1[*].
  Select only owner entries where owner.entity is an object.
  Preserve target coordinates:
    dataIndex
    ownerIndex
    owner node

Initial seed extraction:
  From each selected root entity owner node, read child owner numbers from:
    entity.ownershipStructure[*].principalOwners[*].memberDetails.number
    entity.ownershipStructure[*].indirectOwners[*].memberDetails.number
  Preserve first-seen order and deduplicate seeds.

Recursive fetch:
  Fetch owners by ids through:
    Owners.fetchOwners(request, Owners.DEFAULT_FIELDS, context.clientRequestContext())
  Request body field:
    ids
  Required response shape:
    data must be an array.

Response indexing:
  Each fetched owner is indexed by owner number.
  Number resolution order:
    individual.number
    entity.number
  Blank numbers are ignored.

Missing response item:
  If the downstream response does not contain every requested owner number, treat it as malformed downstream response.
  Do not silently ignore a requested owner missing from the response.

Traversal semantics:
  first downstream level is depth 1
  MAX_DEPTH is 6
  depth values 1 through 6 are allowed
  fail only when attempting depth > 6
  already resolved/visited numbers are not fetched again
  cycle behavior is effectively SKIP_VISITED, not FAIL_ON_CYCLE

Node classification:
  individual owner: owner.individual is an object
  entity owner: owner.entity is an object

Traversal output ordering:
  Individual owners are collected in first-resolution order.
  Preserve this ordering because legacy code uses insertion-order maps for resolved individuals.

Final legacy write shape:
  For each root entity target, write resolved individual owners into:
    root.data[dataIndex].owners1[ownerIndex].beneficialOwnersDetails

Failure behavior:
  depth exceeded -> required enrichment failure / contract violation path
  downstream failure -> required enrichment failure through existing facade mapping
  malformed response -> required enrichment failure / contract violation path
```

### Tests to add only if missing

Before adding tests, inspect existing characterization tests. Add only missing coverage.

Recommended contract scenarios:

```text
1. no entity owners under owners1 -> SKIPPED / NO_KEYS_IN_MAIN
2. root target selection preserves dataIndex and ownerIndex
3. principalOwners and indirectOwners both contribute child numbers
4. duplicated child numbers are fetched once
5. first downstream level is depth 1
6. max depth allows levels 1..6 and fails only beyond 6
7. already resolved / cyclic numbers are not fetched twice
8. missing requested owner in response fails as malformed response / contract violation
9. non-array data response fails as malformed response / contract violation
10. resolved individuals keep first-resolution order
11. merge writes to data[dataIndex].owners1[ownerIndex].beneficialOwnersDetails
12. existing public response shape and meta.parts behavior remain unchanged
```

### Acceptance criteria

```text
The legacy beneficialOwners contract is documented in this plan.
Existing beneficialOwners characterization coverage is reviewed.
Any missing high-risk characterization tests are added.
No production behavior changes.
Phase 14 can start with explicit legacy semantics rather than assumptions.
```

### Verification

Run focused beneficialOwners tests first:

```bash
./gradlew test --tests '*BeneficialOwners*'
```

If only the markdown is changed and no tests are added, record that this is a docs-only contract lock.

### Handoff to Phase 14

After Phase 13.5, Phase 14 must use this contract to shape reusable traversal mechanics. Phase 14 must not reinterpret depth, missing-response, cycle, ordering, or target-addressing semantics.


### Phase 13.5 coding prompt

Use this prompt when starting the next coding session:

```text
Read AGGREGATION_WORKFLOW_MIGRATION_PLAN.md and apply the CLAUDE.md operating rules.

We are starting Phase 13.5 — BeneficialOwners Legacy Contract Lock.

Task: documentation/test-only contract lock for legacy beneficialOwners behavior.

Scope:
- Inspect BeneficialOwnersEnrichment, OwnershipResolver, EntityNumbersExtractor, RootEntityTargets, RootEntityTarget, BeneficialOwnersDetailsPayload, BeneficialOwnersResolutionException, and existing BeneficialOwners tests.
- Do not change production code unless a tiny testability-only adjustment is absolutely required.
- Do not add workflow.recursive yet.
- Do not add RecursiveFetchStep.
- Do not add reducer infrastructure.
- Do not migrate BeneficialOwnersEnrichment.

Before editing:
1. List existing tests that already cover the contract.
2. List only the missing high-risk tests, if any.
3. Propose the smallest test-only change set.
4. Stop if the existing tests already cover the contract well enough and only the plan needs a handoff note.

Required contract points to verify or document:
- dependsOn owners
- no entity owners under owners1 -> SKIPPED / NO_KEYS_IN_MAIN
- root targets are data[*].owners1[*] where owner.entity is an object
- dataIndex and ownerIndex are preserved
- child numbers come from principalOwners and indirectOwners memberDetails.number
- first downstream depth is 1
- max depth is 6
- depth > 6 fails, no silent truncation
- visited/resolved numbers are not fetched twice
- missing requested owner in downstream response is malformed response / contract violation
- individual owners preserve first-resolution order
- output writes to data[dataIndex].owners1[ownerIndex].beneficialOwnersDetails

Verification:
./gradlew test --tests '*BeneficialOwners*'

After completion:
- update the tracker/handoff note for Phase 13.5
- mark Phase 14A as the next coding phase
```

---

## Phase 14 — Recursive Traversal Skeleton

**Status:** Completed (14A/14B/14C).

### Phase 14 structure

Phase 14 is split into three focused substeps:

```text
Phase 14A — Traversal data model
Phase 14B — Recursive traversal engine
Phase 14C — RecursiveFetchStep workflow adapter
```

These substeps may be committed separately or as a small commit group, but they must remain infrastructure-only.

### Phase 14 completion handoff

```text
- Phase 1 through Phase 14C are completed on main.
- Phase 13.5 locked the legacy beneficialOwners contract.
- Phase 14A added the minimal immutable traversal data model.
- Phase 14B added the reusable level-by-level recursive traversal engine.
- Phase 14C added RecursiveFetchStep that stores traversal output as STEP_RESULT JsonNode (no patch/root writes).
- Account and owners are workflow-based enrichments.
- Account declares WriteOwnership.of("account1").
- Owners declares WriteOwnership.of("owners1").
- Binding metrics, multi-binding workflow, CURRENT_ROOT, STEP_RESULT, ComputeStep, patch conflict detection, and write ownership validation are implemented.
- ROOT_SNAPSHOT, CURRENT_ROOT, and STEP_RESULT are supported workflow input sources.
- TRAVERSAL_STATE remains unsupported as a general workflow KeySource. Phase 14 should keep traversal state internal and store traversal outputs through existing STEP_RESULT variables unless later phases prove a new source is necessary.
- beneficialOwners is still legacy and must remain unchanged until Phase 16.
- Phase 15 is the next coding phase.
```

Phase 14 must be infrastructure-only. It should introduce recursive traversal state/mechanics and produce a traversal result, but it must not write to the root, add a business reducer, or migrate `beneficialOwners`.

Important storage decision for Phase 14:

```text
WorkflowVariableStore currently stores JsonNode values.
Therefore RecursiveFetchStep must store the traversal output as JsonNode/ObjectNode/ArrayNode through STEP_RESULT.
Typed traversal records may exist inside workflow.recursive for implementation clarity, but the workflow-visible stored value must remain JsonNode.
Do not change WorkflowVariableStore to Object-based storage in Phase 14.
Do not add a public/general TRAVERSAL_STATE KeySource in Phase 14 unless STEP_RESULT is proven insufficient and the plan is updated first.
```

### Goal

Extract the minimum reusable traversal mechanics needed to model the current `OwnershipResolver` algorithm later.

Do not build a general graph engine.

Pattern:

```text
ROOT_SNAPSHOT seed extraction
  -> batch fetch frontier keys
  -> index response
  -> require all requested keys when configured
  -> classify fetched nodes
  -> extract child keys
  -> repeat until empty frontier or max depth
  -> store TraversalResult as STEP_RESULT
```

### Design intent

Good Phase 14 design:

```text
- simple frontier-based breadth-first traversal
- deterministic first-seen ordering
- explicit maxDepth
- explicit visited/resolved set
- explicit child-key extraction callback/configuration
- explicit node classification callback/configuration
- explicit response indexing by configured key extractor
- TraversalResult stored as a named STEP_RESULT value encoded as JsonNode
```

Avoid in Phase 14:

```text
- generic graph query language
- complicated graph algorithms
- parallel frontier scheduler
- business-specific beneficialOwners output reduction
- root patch creation
- public response changes
- a second path DSL
```

### New package

```text
dev.abramenka.aggregation.workflow.recursive
```

### Phase 14A — Traversal data model

#### Goal

Introduce the smallest immutable model needed to represent traversal state and results.

#### Candidate classes

```text
TraversalPolicy
CyclePolicy
TraversalNode
TraversalResult
TraversalState
```

Optional only if the implementation genuinely benefits from them:

```text
TraversalFrontier
DepthLimit
```

Defer unless there is a real need:

```text
TraversalStopCondition
```

For the first version, `TraversalPolicy` should be enough for:

```text
maxDepth
cyclePolicy = SKIP_VISITED
requireAllRequestedKeys = true for beneficialOwners-compatible traversal
```

#### Required model properties

```text
TraversalResult must preserve enough information for Phase 15 reducer. If represented by typed internal records, it must also have a deterministic JsonNode representation for STEP_RESULT storage:
  - resolved nodes
  - individual/entity classification or enough raw node data to classify later
  - stable first-resolution order
  - depth information if useful for tests/debugging
  - target metadata must be supported by the surrounding workflow/reducer input, not necessarily by traversal core
```

Important target-metadata rule:

```text
Traversal core should not know about beneficialOwners-specific dataIndex/ownerIndex.
Those coordinates belong to root-target selection / reducer input in Phase 15/16.
```

#### Acceptance criteria

```text
Traversal model is small and immutable where practical.
No downstream calls.
No WorkflowExecutor changes unless unavoidable.
No root writes.
No beneficialOwners migration.
```

#### Phase 14A coding prompt

Use this prompt only after Phase 13.5 is completed:

```text
Read AGGREGATION_WORKFLOW_MIGRATION_PLAN.md and apply the CLAUDE.md operating rules.

We are starting Phase 14A — Traversal data model.

Task: add only the minimal recursive traversal data model.

Scope:
- Add package dev.abramenka.aggregation.workflow.recursive if needed.
- Add minimal model classes only: TraversalPolicy, CyclePolicy, TraversalNode, TraversalResult, and only add TraversalState if tests/implementation need it.
- Do not add RecursiveFetchStep yet.
- Do not add traversal engine yet.
- Do not call downstream services.
- Do not touch BeneficialOwnersEnrichment.
- Do not change WorkflowVariableStore.
- Do not add TRAVERSAL_STATE support as a public/general KeySource.

Before editing:
1. Inspect WorkflowVariableStore and StepResult.
2. Confirm traversal result must be representable as JsonNode for STEP_RESULT storage.
3. Propose exact classes and tests.
4. Stop if the model needs Object-based workflow variables or a generic graph framework.

Verification:
./gradlew test --tests '*Traversal*'
```


### Phase 14B — Recursive traversal engine

#### Goal

Implement the reusable level-by-level traversal algorithm.

The engine should be close to the existing `OwnershipResolver` algorithm, but without beneficial-owner-specific write/reducer logic.

#### Required capabilities

```text
- accept initial seed keys
- preserve first-seen seed order
- deduplicate seeds
- fetch one frontier batch at a time
- index response items by configured key
- optionally require all requested keys to be present
- classify fetched nodes
- collect terminal nodes such as individuals
- extract child keys from expandable nodes such as entities
- skip already visited/resolved keys
- stop successfully on empty frontier
- enforce maxDepth with legacy-compatible semantics
```

#### BeneficialOwners-compatible semantics to preserve

```text
first downstream level is depth 1
MAX_DEPTH = 6 for beneficialOwners
levels 1..6 are allowed
failure occurs only when attempting depth > 6
cycle behavior = SKIP_VISITED
missing requested response item = malformed response / contract violation
non-array response data = malformed response / contract violation
individual results preserve first-resolution order
```

#### Error guidance

The traversal engine may throw/propagate internal exceptions, but public RFC 9457 mapping must remain owned by the facade/orchestration layer.

```text
Depth exceeded:
  deterministic failure, later mapped through existing required-enrichment failure path

Missing requested response item:
  malformed response / contract violation

Malformed response shape:
  malformed response / contract violation

Downstream call failure:
  preserve existing downstream error normalization path
```

#### Acceptance criteria

```text
Recursive traversal fetches nodes level by level.
Visited keys are not fetched twice.
Cycle behavior is SKIP_VISITED.
Stops successfully on empty frontier.
Stops/fails deterministically on maxDepth overflow.
No JsonPatchDocument/root write is produced.
No reducer logic.
No beneficialOwners migration.
```

### Phase 14C — RecursiveFetchStep workflow adapter

#### Goal

Connect the traversal engine to the workflow runtime as a `WorkflowStep`.

`RecursiveFetchStep` should normally return a stored workflow-visible result encoded as JsonNode:

```text
StepResult.stored(storeAs, traversalResultJson)
```

or the project-equivalent `StepResult.Applied` variant with `patch == null`, `storeAs != null`, and `storedValue != null`, depending on the current `StepResult` API.

`storedValue` must be a JsonNode because the current workflow variable store is JsonNode-based.

It must not return root patch operations in Phase 14.

#### Rules

```text
- read initial traversal seeds from ROOT_SNAPSHOT for the first version
- store traversal result as JsonNode in the existing workflow-local variable store
- do not mutate ROOT_SNAPSHOT
- do not mutate CURRENT_ROOT
- do not apply JsonPatchDocument
- do not write to the global root
- do not expose traversal details in public response bodies
- keep traversal state internal to RecursiveFetchStep/recursive package; keep KeySource.TRAVERSAL_STATE unsupported outside recursive traversal in Phase 14
```

### Tests required

Add focused tests for recursive traversal infrastructure, including:

```text
1. seed keys are extracted from ROOT_SNAPSHOT or supplied to the engine in first-seen order
2. first downstream level is depth 1
3. level-by-level fetching happens in deterministic order
4. duplicate seeds are fetched once
5. already visited child keys are not fetched again
6. empty initial seeds produce the chosen explicit empty/skip behavior or empty TraversalResult
7. empty next frontier completes traversal successfully
8. maxDepth allows levels 1..maxDepth and fails only beyond maxDepth
9. downstream response missing a requested key fails as malformed response / contract violation
10. data response that is not an array fails as malformed response / contract violation
11. individual nodes are collected in first-resolution order or preserved in TraversalResult
12. entity nodes produce next frontier through configured child-key extraction
13. traversal result is stored as STEP_RESULT JsonNode
14. no JsonPatchDocument/root write is produced
15. account and owners workflows remain unchanged
```

### Verification

Run focused traversal tests first. Also run workflow executor tests if `StepResult`, variable storage, or `WorkflowExecutor` is touched.

Suggested commands:

```bash
./gradlew test --tests '*Recursive*'
./gradlew test --tests '*Traversal*'
./gradlew test --tests '*WorkflowExecutorTest'
./gradlew test --tests '*Account*'
./gradlew test --tests '*Owners*'
```

### Stop and report instead of guessing if

```text
- preserving legacy beneficialOwners depth semantics requires a different traversal model
- missing-response behavior conflicts with existing downstream helper behavior
- traversal cannot store its result as JsonNode in the existing workflow variable store
- traversal requires root patch generation in Phase 14
- a generic graph engine seems necessary
- current code contradicts this plan
```

### Forbidden

```text
- No beneficialOwners migration yet.
- No business-specific reducer in traversal core.
- No root writes.
- No JsonPatchDocument generation for beneficialOwners output.
- No complex graph algorithms beyond the needed traversal state.
- No parallel traversal scheduler.
- No public response shape change.
- No RFC 9457 contract change unless existing error mapping already requires it.
```

---

## Phase 15 — Traversal Reducer and Write-back Bridge

**Status:** Completed.

### Goal

Support recursive collection, aggregation, and write-back after `RecursiveFetchStep` has produced a `TraversalResult`.

Pattern:

```text
ROOT_SNAPSHOT target selection
  + STEP_RESULT traversal result
  -> reducer computes final per-target values
  -> reducer returns JsonPatchDocument or a workflow write instruction
  -> WorkflowExecutor applies the patch to CURRENT_ROOT and accumulated final patch
```

### Phase 15 boundary

Phase 15 adds reducer/write-back infrastructure only.

It must not migrate `beneficialOwners` yet. The reducer may be tested with fixtures shaped like beneficial-owner traversal data, but production `BeneficialOwnersEnrichment` should remain legacy until Phase 16.

### Recommended Phase 15 split

```text
Phase 15A — reducer input/output model
Phase 15B — beneficialOwners-compatible reducer tests using fake TraversalResult
Phase 15C — JsonPatch generation for beneficialOwnersDetails target field
```

The reducer infrastructure should be generic enough for traversal results, but it should not hide the concrete target-addressing needs of `beneficialOwners`.

### New abstractions

```text
TraversalReducer
TraversalReductionInput
TraversalReductionResult
```

Defer unless tests prove a concrete need:

```text
TraversalWriteRule
```

Do not introduce a generic write-rule DSL in Phase 15. For the first reducer, producing a normal `JsonPatchDocument` is preferred.

Recommended execution model:

```text
Add a dedicated reducer workflow step that returns StepResult.applied(patch).
Do not overload ComputeStep for reducer write-back, because ComputeStep is intentionally pure and stores JsonNode results only.
```

### Reducer requirements

Traversal core should not contain beneficial-owner-specific business logic.

Business-specific aggregation belongs in reducer classes.

A reducer may:

```text
- read traversal result from STEP_RESULT JsonNode and convert/validate it explicitly
- read ROOT_SNAPSHOT or CURRENT_ROOT through declared inputs
- collect nodes
- compute final JsonNode values
- return a JsonPatchDocument through a dedicated reducer workflow step
```

A reducer must not:

```text
- call downstream services
- mutate global root directly
- mutate ROOT_SNAPSHOT directly
- apply patches directly outside WorkflowExecutor
- build HTTP error bodies
- bypass WorkflowExecutor
```

### BeneficialOwners-compatible reducer contract

The Phase 15 reducer shape must be able to support the legacy beneficial-owners output in Phase 16:

```text
For each root entity target:
  identify root.data[dataIndex].owners1[ownerIndex]
  compute details array from resolved individual owner nodes
  write details to field beneficialOwnersDetails
```

The reducer infrastructure must preserve or receive enough target metadata for that write:

```text
dataIndex
ownerIndex
stable target identity or pointer
resolved individual nodes in legacy-compatible first-resolution order
```

Recommended ownership of target metadata:

```text
Traversal core should not own dataIndex/ownerIndex.
A Phase 15 reducer input, root target descriptor, or workflow step configuration should provide this target identity.
This keeps recursive traversal reusable and keeps beneficialOwners write-back business-specific.
```

Recommended write target for beneficialOwners-compatible tests:

```text
/data/{dataIndex}/owners1/{ownerIndex}/beneficialOwnersDetails
```

Use project JSON Pointer builder helpers rather than manual string concatenation where possible.

### Acceptance criteria

```text
A STEP_RESULT traversal JsonNode can be reduced into one or more JsonPatchDocument operations.
Reducer is independently unit-testable.
Reducer can write to nested owner target fields without mutating the root directly.
Reducer preserves stable ordering of collected detail nodes.
Reducer errors map deterministically.
No beneficialOwners migration yet.
Existing account and owners workflows remain unchanged.
```

### Tests required

Add focused reducer tests, including:

```text
1. reducer reads traversal result from STEP_RESULT JsonNode or equivalent workflow state
2. reducer writes a nested field under a target owner
3. reducer produces JsonPatchDocument with escaped JSON Pointer tokens where needed
4. reducer preserves detail ordering
5. reducer does not call downstream
6. reducer does not mutate root directly
7. invalid traversal payload shape maps deterministically
8. patch/write failure maps to ORCH-MERGE-FAILED
9. existing workflow executor multi-step tests still pass
```

### Error mapping guidance

```text
Invalid traversal payload shape:
  ENRICH-CONTRACT-VIOLATION or ENRICH-INVALID-PAYLOAD

Reducer invariant failure:
  ORCH-INVARIANT-VIOLATED

Patch/write failure:
  ORCH-MERGE-FAILED
```

### Forbidden

```text
- Do not migrate beneficialOwners yet.
- Do not put beneficial-owner-specific traversal rules into traversal core.
- Do not call downstream services from reducers.
- Do not mutate the global root directly.
- Do not bypass WorkflowExecutor patch handling.
- Do not change public response shape.
```

---

## Phase 16 — Migrate Beneficial Owners

**Status:** Not started.

### Goal

Migrate the recursive beneficial owners enrichment to workflow traversal.

This is the main proof that the workflow model can handle a non-trivial recursive enrichment without embedding that business logic into the core workflow engine.

### Preconditions

Do not start Phase 16 until:

```text
- Phase 13.5 legacy beneficialOwners contract lock is completed.
- Phase 14 recursive traversal skeleton is completed and focused tests are green.
- Phase 15 traversal reducer infrastructure is completed and focused tests are green.
- beneficialOwners characterization tests are reviewed.
- account and owners workflow tests are still green.
- owners behavior and owners1 output shape remain unchanged.
```

### Recommended Phase 16 split

```text
Phase 16A — introduce workflow-based BeneficialOwnersEnrichment behind the existing behavior contract
Phase 16B — switch production beneficialOwners execution to traversal + reducer
Phase 16C — retire or isolate the legacy resolver path only after characterization tests prove parity
```

Phase 16 is migration, not traversal design. If Phase 16 reveals missing traversal or reducer capabilities, stop and either fix Phase 14/15 infrastructure explicitly or update this plan. Do not silently design new traversal mechanics inside the business migration.

### Migration rule

Before:

```text
beneficialOwners uses business-specific recursive traversal/orchestration code.
```

After:

```text
beneficialOwners extends WorkflowAggregationPart.
beneficialOwners declares traversal configuration and reducer logic.
Recursive workflow infrastructure handles traversal mechanics.
Business-specific reducer handles beneficial-owner output shape.
```

### Behavior to preserve exactly

```text
Part name:
  beneficialOwners

Dependencies:
  owners

Criticality:
  Preserve existing required behavior unless tests prove otherwise.

Skip behavior:
  If no entity owners exist under owners1, return SKIPPED / NO_KEYS_IN_MAIN.

Seed targets:
  root.data[*].owners1[*] where owner.entity is an object.
  Preserve dataIndex and ownerIndex for final write-back.

Seed child numbers:
  entity.ownershipStructure[*].principalOwners[*].memberDetails.number
  entity.ownershipStructure[*].indirectOwners[*].memberDetails.number

Recursive fetch:
  Owners.fetchOwners(request(ids), Owners.DEFAULT_FIELDS, context.clientRequestContext())

Depth behavior:
  first downstream level is depth 1
  MAX_DEPTH is 6
  fail only when depth > 6

Cycle behavior:
  already resolved/visited owner numbers are skipped, not failed.

Missing requested owner:
  malformed response / required enrichment failure; do not ignore silently.

Final output:
  write resolved individual owner nodes to:
    root.data[dataIndex].owners1[ownerIndex].beneficialOwnersDetails

Metrics:
  Preserve existing business-level behavior and public meta.parts.
  Preserve or intentionally replace the internal beneficial_owners tree metric only with documented tests.
```

### Suggested workflow shape

```text
Step 1:
  identify root entity targets and traversal seeds from ROOT_SNAPSHOT

Step 2:
  RecursiveFetchStep fetches owners recursively and stores TraversalResult as STEP_RESULT

Step 3:
  Dedicated reducer workflow step reads target metadata + traversal STEP_RESULT JsonNode and produces JsonPatchDocument

Final:
  WorkflowExecutor applies patch to CURRENT_ROOT and accumulated final patch
  AggregationPartExecutor applies final part patch to global root once
```

The exact class split may differ, but these boundaries must remain true:

```text
Traversal step owns recursion mechanics.
Reducer owns beneficial-owner output shape.
WorkflowExecutor owns patch application semantics.
BeneficialOwnersEnrichment owns only workflow declaration and business reducer wiring.
```

### Acceptance criteria

```text
Existing beneficialOwners tests pass unchanged or with intentionally documented contract updates.
Depth limit behavior unchanged.
Cycle behavior unchanged.
Downstream failure behavior unchanged.
Missing requested owner behavior unchanged.
No public response shape change unless explicitly documented.
No success-side meta.parts change unless explicitly documented.
No RFC 9457 response structure change.
No manual recursive orchestration remains in BeneficialOwnersEnrichment beyond reducer/configuration.
Account behavior unchanged.
Owners behavior unchanged.
```

### Tests required

At minimum, verify:

```text
1. no root entity owners -> SKIPPED / NO_KEYS_IN_MAIN
2. seed extraction from owners1 entity owners
3. principalOwners child numbers are traversed
4. indirectOwners child numbers are traversed
5. individual owners are collected into beneficialOwnersDetails
6. nested entity owners are traversed recursively
7. duplicate/cyclic owner numbers are not fetched repeatedly
8. depth limit preserves legacy off-by-one semantics
9. missing requested owner response fails as malformed/contract violation
10. downstream failure maps through existing required-enrichment error path
11. final response JSON shape matches legacy output
12. global root is still patched once by AggregationPartExecutor
13. account and owners workflow tests remain green
```

### Verification

Because Phase 14 through Phase 16 are a risky batch checkpoint, run or explicitly rely on CI for the full verification after Phase 16:

```bash
./gradlew test
./gradlew spotlessJavaCheck verifyBoot4Classpath jacocoTestCoverageVerification
./gradlew build
```

### Forbidden

```text
- Do not change account or owners behavior in this phase.
- Do not rewrite traversal core specifically for beneficialOwners.
- Do not introduce a generic graph query language.
- Do not introduce public JSON Patch API.
- Do not change public REST API.
- Do not change RFC 9457 public response structure.
- Do not hide changed behavior behind test rewrites.
```

---

## Phase 17 — Optional Root Role Abstraction

**Status:** Not started.

### Goal

Evaluate whether the current root/main downstream naming is too account-specific for future workflows.

This phase is optional and should be done only if earlier phases show that root naming creates real friction.

### Candidate abstractions

```text
RootDocument
RootRole
MainDependency
PrimaryDocument
```

### Rule

Do not rename working, stable concepts just for aesthetics.

Only introduce this abstraction if it simplifies real workflow code or removes misleading account-specific naming from generic workflow infrastructure.

### Acceptance criteria

```text
If implemented, generic workflow code no longer depends on account-specific root naming.
Public API unchanged.
Existing behavior unchanged.
Tests updated only where names/types changed.
```

### Forbidden

```text
- No public response contract change.
- No broad rename-only PR unless the benefit is concrete.
- No migration of unrelated business code.
```

---

## Phase 18 — Documentation, Test Kit, and Examples

**Status:** Not started.

### Goal

Make the new workflow authoring style understandable and safe for future developers and coding agents.

### Required documentation

```text
- How to create a simple keyed workflow part.
- How to choose ROOT_SNAPSHOT vs CURRENT_ROOT vs STEP_RESULT.
- How to define a DownstreamBinding.
- How to define response indexing rules.
- How write rules map to JsonPatchDocument.
- How soft outcomes map to meta.parts.
- How required/optional criticality affects failures.
- How to test a workflow part.
- How to use fff to navigate workflow-related code.
```

### Test kit candidates

```text
WorkflowPartTestSupport
DownstreamBindingTestFixtures
JsonNodeFixtureBuilder
WorkflowExecutionAssertions
```

### Examples

Provide at least:

```text
- one simple keyed enrichment example
- one fallback-key enrichment example
- one multi-binding example
- one recursive traversal example if Phase 16 is complete
```

### Acceptance criteria

```text
A new developer can author a simple workflow part without reading every internal class.
Examples compile or are clearly marked as documentation-only pseudocode.
Tests demonstrate recommended patterns.
No public API behavior changed.
```

---

## Phase 19 — Retire Legacy Enrichment Authoring

**Status:** Not started.

### Goal

After all target enrichments are migrated and stabilized, remove or deprecate legacy authoring paths that are no longer needed.

### Preconditions

Do not start Phase 19 until:

```text
- account is migrated
- owners is migrated
- beneficialOwners is migrated, if still in project scope
- workflow docs and examples exist
- CI is green on main
- public behavior is verified by characterization tests
```

### Allowed changes

```text
- remove unused legacy helper classes
- deprecate old authoring APIs if removal is too risky
- simplify tests that only existed for legacy internals
- keep characterization tests that protect public behavior
```

### Forbidden

```text
- Do not remove public behavior.
- Do not remove tests that protect API contracts.
- Do not combine with new feature work.
- Do not change RFC 9457 error contract.
- Do not change meta.parts contract.
```

### Acceptance criteria

```text
No unused legacy enrichment authoring path remains, or it is explicitly deprecated.
Workflow authoring is the documented default.
Full local/global verification or CI verification is green.
Migration plan is updated with final status.
```

---

## Final Migration Checklist for main

Before declaring the workflow migration complete on `main`, verify:

```text
- migration tracker is accurate
- all completed phases have handoff notes
- no phase accidentally changed public response shape without documentation
- no downstream details leak into public error bodies
- meta.parts remains stable unless explicitly documented
- RFC 9457 problem responses remain facade-owned
- fff search confirms no duplicate old/new implementations remain unintentionally
- full verification has run locally or CI is green for equivalent checks
```

Recommended final commands:

```bash
./gradlew test
./gradlew spotlessJavaCheck verifyBoot4Classpath jacocoTestCoverageVerification
./gradlew build
```
