# Aggregation Gateway Workflow Migration Plan

> **Purpose:** This document is a sequential, agent-friendly migration plan for evolving the current Aggregation Gateway from hand-written enrichment parts into a more general, workflow-capable enrichment architecture.
>
> **Primary goal:** adding a new enrichment should require only a small set of descriptive classes: part name, dependencies, downstream bindings, endpoint-specific key extraction rules, response indexing rules, write/patch rules, and optional compute/reduce logic. The existing engine should automatically handle planning, execution, errors, metrics, and JSON output mutation.
>
> **Current status:** Phase 1 through Phase 9 are completed on `main`. Phase 10 — migrate `owners` — is the next implementation phase.
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

This section reflects the current repository state after Phases 7 through 9 were merged directly into `main`.

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
- after Phase 14 through Phase 16
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
[ ] Phase 10 — Migrate owners
[ ] Phase 11 — Multi-binding workflow
[ ] Phase 12 — Compute step
[ ] Phase 13 — Harden patch conflict detection and write ownership
[ ] Phase 14 — Recursive traversal skeleton
[ ] Phase 15 — Traversal reducer
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

**Status:** Not started.

### Preconditions

Phase 10 starts from the actual `main` implementation after Phases 7 through 9.

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

**Status:** Not started.

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

**Status:** Not started.

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

**Status:** Not started.

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

## Phase 14 — Recursive Traversal Skeleton

**Status:** Not started.

### Goal

Support recursive downstream traversal.

Pattern:

```text
A -> keys -> REST Z recursively until condition
```

### New package

```text
dev.abramenka.aggregation.workflow.recursive
```

### New classes

```text
RecursiveFetchStep
TraversalState
TraversalNode
TraversalFrontier
TraversalPolicy
TraversalStopCondition
CyclePolicy
DepthLimit
TraversalResult
```

### Required capabilities

```text
- seed keys from ROOT_SNAPSHOT
- batch fetch frontier keys
- extract child keys from each response node
- deduplicate visited keys
- stop on empty frontier
- stop on max depth
- handle cycle policy
```

### Policy shape

```java
public record TraversalPolicy(
    int maxDepth,
    CyclePolicy cyclePolicy,
    TraversalStopCondition stopCondition
) {}
```

### Acceptance criteria

```text
Recursive traversal fetches Z level by level.
Stops on empty frontier.
Stops on maxDepth.
Detects cycles.
Returns TraversalResult.
Does not write to root yet.
```

### Forbidden

```text
- No beneficialOwners migration yet.
- No business-specific reducer in traversal core.
- No complex graph algorithms beyond the needed traversal state.
```

---

## Phase 15 — Traversal Reducer

**Status:** Not started.

### Goal

Support recursive collection, aggregation, and write-back.

Pattern:

```text
A -> keys -> REST Z recursively
  + collect data on each step
  + aggregate after traversal
  + write into original A
```

### New abstractions

```text
TraversalReducer
TraversalReductionInput
TraversalReductionResult
TraversalWriteRule
```

### Rule

Traversal core should not contain beneficial-owner-specific business logic.

Business-specific aggregation belongs in reducer classes.

A reducer may:

```text
- read TraversalResult
- collect nodes
- compute final JsonNode values
- return a JsonPatchDocument or a write instruction consumed by workflow writing code
```

A reducer must not:

```text
- call downstream services
- mutate global root directly
- build HTTP error bodies
- bypass WorkflowExecutor
```

### Acceptance criteria

```text
TraversalResult can be reduced into one or more JsonPatchDocument operations.
Reducer is independently unit-testable.
Reducer errors map deterministically.
No beneficialOwners migration yet.
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

---

## Phase 16 — Migrate Beneficial Owners

**Status:** Not started.

### Goal

Migrate the recursive beneficial owners enrichment to workflow traversal.

This is the main proof that the workflow model can handle a non-trivial recursive enrichment without embedding that business logic into the core workflow engine.

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

### Acceptance criteria

```text
Existing beneficialOwners tests pass unchanged or with intentionally documented contract updates.
Depth limit behavior unchanged.
Cycle behavior unchanged.
Downstream failure behavior unchanged.
No public response shape change unless explicitly documented.
No manual recursive orchestration remains in beneficialOwners beyond reducer/configuration.
```

### Forbidden

```text
- Do not change account or owners behavior in this phase.
- Do not rewrite traversal core specifically for beneficialOwners.
- Do not introduce a generic graph query language.
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
