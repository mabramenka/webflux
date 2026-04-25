# Aggregation Gateway Workflow Migration Plan

> **Purpose:** This document is a sequential, agent-friendly migration plan for evolving the current Aggregation Gateway from hand-written enrichment parts into a more general, workflow-capable enrichment architecture.
>
> **Primary goal:** adding a new enrichment should require only a small set of descriptive classes: part name, dependencies, downstream bindings, endpoint-specific key extraction rules, response indexing rules, write/patch rules, and optional compute/reduce logic. The existing engine should automatically handle planning, execution, errors, metrics, and JSON output mutation.
>
> **Current status:** Phase 1 and Phase 2 are already completed.

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
- Every phase must be independently mergeable.
```

---

## 2. Current Project Context

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

## 3. Target Architecture

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

## 4. Desired Developer Experience

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

## 5. Error and Patch Strategy

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

Workflow phases in this plan should map to the existing problem catalog codes rather than
introducing new public error categories by wording alone.

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

For the first workflow phases, reuse the existing public reason `NO_KEYS_IN_MAIN`
when keys are missing from `ROOT_SNAPSHOT`, because this preserves the current contract.

For later sources such as `STEP_RESULT` or `TRAVERSAL_STATE`, do not invent public enum
values casually. Either:

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

## 6. Non-goals

Do not implement these during this migration unless a later document explicitly asks for them:

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

# 7. Sequential Migration Phases

Each phase below is intended to be done by one agent or one small PR.

### Execution protocol

Use this plan as a strict sequential runbook:

```text
- exactly one phase per PR
- do not start the next phase until the previous PR is merged
- after merge, re-read the codebase and this plan before starting the next phase
- if a phase reveals missing prerequisite work, stop and update the plan instead of silently
  folding extra architecture into the same PR
```

This file may also be used as a local progress tracker even if it is not committed.
An agent may update the status checklist below in the working tree to mark progress for the
current operator.

### Local phase tracker

```text
[x] Phase 1  — Characterization tests
[x] Phase 2  — Internal JSON Patch model
[x] Phase 3  — Patch builder helpers
[x] Phase 4  — Downstream binding model
[x] Phase 5  — Adapt existing keyed support
[ ] Phase 6  — Workflow model skeleton
[ ] Phase 7  — Keyed binding step
[ ] Phase 8  — Migrate account
[ ] Phase 9  — Binding metrics and diagnostics
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
- Tests
- Small focused diff
- No unrelated cleanup
- Clear handoff note
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
./gradlew test passes
No public behavior changed
Tests clearly document current contract
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
Existing MergePatch and ReplaceDocument behavior unchanged
Existing tests pass
New tests for add/replace/test pass
Patch failure maps to existing orchestration merge failure for now
```

### Forbidden

```text
- Do not remove MergePatch
- Do not migrate any enrichment yet
- Do not expose JSON Patch in HTTP API
```

---

## Phase 3 — Patch Builder Helpers

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
Builder escapes "~" and "/" correctly
Builder can append to arrays
Builder does not silently create missing intermediate objects unless explicitly configured
Patch builder tests pass
```

### Forbidden

```text
- No business enrichment migration
- No support for remove/move/copy yet
```

---

## Phase 4 — Downstream Binding Model

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
    String name,
    KeySource keySource,
    String sourceItemPath,
    List<String> keyPaths,
    DownstreamCall downstreamCall,
    String responseItemPath,
    List<String> responseKeyPaths,
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
Binding model exists
Invalid binding definitions fail fast
Unit tests validate binding construction
No public behavior changed
```

### Forbidden

```text
- Do not use the binding model in production flow yet
- Do not migrate existing enrichments yet
```

---

## Phase 5 — Adapt Existing Keyed Support

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

### Path dialect constraint

```text
Keep workflow bindings on the same limited path dialect already supported by PathExpression.
Do not expand this phase into a JSONPath implementation project.
```

### Acceptance criteria

```text
Existing keyed enrichments still work
New binding tests reuse existing path/key extraction logic
No duplicate key extraction implementation
No full JSONPath dependency added
```

### Forbidden

```text
- Do not remove EnrichmentRule yet
- Do not change path syntax broadly
- Do not add filter/index/slice JSONPath semantics
```

---

## Phase 6 — Workflow Model Skeleton

### Goal

Introduce workflow as an implementation style for `AggregationPart`.

### New package

```text
dev.abramenka.aggregation.workflow
```

### New classes

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

### Suggested model

```java
public record AggregationWorkflow(
    String name,
    Set<String> dependencies,
    PartCriticality criticality,
    List<WorkflowStep> steps
) {}
```

### Adapter

```java
public abstract class WorkflowAggregationPart implements AggregationPart {

    private final AggregationWorkflow workflow;
    private final WorkflowExecutor workflowExecutor;

    @Override
    public String name() {
        return workflow.name();
    }

    @Override
    public Set<String> dependencies() {
        return workflow.dependencies();
    }

    @Override
    public PartCriticality criticality() {
        return workflow.criticality();
    }

    @Override
    public Mono<AggregationPartResult> execute(AggregationContext context) {
        return workflowExecutor.execute(workflow, context);
    }
}
```

### Important

Do not modify `AggregationPartPlanner`.

A workflow-based part must be just another `AggregationPart` bean.

Invalid workflow definitions should fail fast during startup rather than surfacing first
through a late runtime request or a narrow test.

### Acceptance criteria

```text
A dummy WorkflowAggregationPart can be registered as a Spring bean
Planner discovers it automatically
Executor executes it as a normal AggregationPart
Existing parts are unaffected
Duplicate step names, broken step-result references, invalid path syntax, and invalid
binding/output definitions fail during startup validation
```

### Forbidden

```text
- No existing part migration yet
- No recursive traversal yet
- No compute framework yet
```

---

## Phase 7 — Keyed Binding Step

### Goal

Implement the simple keyed enrichment pattern using the binding model.

Pattern:

```text
A -> endpoint-specific keys -> REST B -> insert/replace result in A
```

### First implementation option

A combined step is acceptable for the first version:

```text
KeyedBindingStep
```

Internally it may perform:

```text
1. Select source items.
2. Extract keys.
3. Deduplicate request keys.
4. Call downstream.
5. Handle 404/empty as soft outcomes.
6. Index response.
7. Match response entries to source targets.
8. Generate JsonPatchDocument.
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

For non-root sources, follow the soft outcome reason rule from section 5.

### Fatal outcome rules

```text
Auth failure:
  RFC 9457 dependency failure

Timeout / unavailable:
  RFC 9457 dependency failure

Invalid payload:
  RFC 9457 dependency failure

Patch failure:
  RFC 9457 orchestration failure
```

### Acceptance criteria

```text
Can express account/owners-style enrichment through a binding
No manual merge code needed in a new workflow part
meta.parts still produced by existing AggregateService/Executor path
Soft/fatal behavior matches existing behavior
```

### Forbidden

```text
- No multi-binding workflow yet
- No compute step yet
- No recursive traversal yet
```

---

## Phase 8 — Migrate One Simple Enrichment

### Goal

Prove the workflow model by migrating one existing simple enrichment.

### Preferred first candidate

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
Account part extends WorkflowAggregationPart
Account part declares one DownstreamBinding
Workflow engine handles extraction/fetch/index/write
```

### Acceptance criteria

```text
All old account tests pass unchanged
No public response shape change
No error contract change
No meta.parts change
Manual execute/merge code removed only for account
Other parts untouched
```

### Forbidden

```text
- Do not migrate owners in the same PR
- Do not migrate beneficialOwners in the same PR
- Do not change root source
```

---

## Phase 9 — Binding Metrics and Diagnostics

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
Existing part metrics unchanged
New binding metrics emitted for workflow parts
No binding details leaked to public error bodies
```

---

## Phase 10 — Migrate Second Simple Enrichment

### Goal

Migrate a keyed enrichment with fallback key paths.

### Preferred candidate

```text
owners
```

This validates:

```text
- multiple key paths
- fallback source keys
- fallback response keys
- write target field
```

### Acceptance criteria

```text
Owners behavior unchanged
Fallback key extraction works
Fallback response indexing works
No manual keyed join logic remains in owners part
Existing owners tests pass
```

### Forbidden

```text
- Do not migrate beneficialOwners yet
- Do not introduce recursive traversal here
```

---

## Phase 11 — Multi-binding Workflow

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

```text
CURRENT_ROOT in this phase means the workflow-local working document for the current
business part, not the global mutable root from AggregationPartExecutor.
```

### Minimal safety contract for this phase

This phase implements only minimal local conflict rejection required for safe multi-binding execution.

Full write ownership and broader conflict validation are handled later in Phase 13.

Before the later dedicated patch-conflict phase, the first multi-binding implementation
must already reject these cases deterministically:

```text
1. same final path written twice with different values
2. add and replace on the same final path
3. write into a missing parent path
4. failed test operation
```

```text
Initial mapping for these failures remains ORCH-MERGE-FAILED.
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
A workflow with two bindings can run
A later binding can extract keys from an earlier binding result
Independent bindings are allowed but not necessarily parallel
Final patch combines writes from multiple bindings
Patch operations are applied once at part end
Bindings may be fetch-only via storeAs(...) without forcing immediate root writes
```

### Forbidden

```text
- No recursive traversal yet
- No complex scheduler
- No automatic parallel dependency planner inside workflow yet
```

---

## Phase 12 — Compute Step

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
Can fetch D and E, compute scalar, write scalar into A
Computation errors map deterministically
No downstream logic inside computation class
Tests cover happy path and compute failure
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
Conflicting patch fails deterministically
No partial patch is applied after failure
Failure returns RFC 9457 problem response
Tests cover conflicts and failed test operations
Declared write ownership is checked against obviously conflicting workflow definitions
```

---

## Phase 14 — Recursive Traversal Skeleton

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
Recursive traversal fetches Z level by level
Stops on empty frontier
Stops on maxDepth
Detects cycles
Returns TraversalResult
Does not write to root yet
```

### Forbidden

```text
- No beneficialOwners migration yet
- No business-specific reducer in traversal core
- No complex graph algorithms beyond the needed traversal state
```

---

## Phase 15 — Traversal Reducer

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
TraversalAggregationResult
TraversalWriteRule
```

### Interface

```java
public interface TraversalReducer {
    JsonNode reduce(TraversalResult traversalResult, WorkflowContext context);
}
```

### Flow

```text
1. RecursiveFetchStep returns TraversalResult.
2. TraversalReducer aggregates collected nodes.
3. WriteJsonPatchStep writes result to original A.
```

### Acceptance criteria

```text
Can collect nodes from all traversal levels
Can deduplicate by business key
Can write aggregate result into root A
No business-specific owner logic in recursive framework
```

---

## Phase 16 — Migrate Beneficial Owners

### Goal

Move the existing recursive beneficial owners enrichment to the new traversal framework.

### Worked example: beneficialOwners

The target migration should preserve the current concrete flow rather than replace it
with an abstract recursive demo that happens to look similar.

Current business flow, restated in workflow terms:

```text
1. Part name stays beneficialOwners.
2. Part still depends on owners.
3. Seed root entities are collected from ROOT_SNAPSHOT using the existing root targeting
   logic after the owners dependency has already been applied at the part-executor level.
4. For each root entity, seed child owner numbers are extracted.
5. Recursive traversal batches requests through the existing owners downstream client.
6. Traversal stops on:
   - empty frontier
   - already visited keys
   - max depth
7. Response nodes are split by business meaning:
   - individual nodes are collected into the final result set
   - entity nodes contribute more child numbers to the next frontier
8. Missing or malformed requested owner entries remain contract violations.
9. Final write still populates beneficialOwnersDetails on the original root entity.
10. Existing tree metrics and public meta.parts behavior stay stable.
```

Illustrative pseudocode:

```java
super(AggregationWorkflow.builder("beneficialOwners")
    .dependsOn("owners")
    .recursiveBinding("ownersTraversal")
        .source(KeySource.ROOT_SNAPSHOT)
        .seedItems("$.data[*].basicDetails.owners[*]")
        .seedKeys("number")
        .call(keys -> ownersClient.fetch(keys))
        .responseItems("$.data[*]")
        .responseKeys("individual.number", "id")
        .traversalPolicy(BeneficialOwnersTraversalPolicy.defaults())
        .storeAs("ownersTraversal")
        .endBinding()
    .reduce(new BeneficialOwnersReducer())
    .write()
        .targetItems("$.data[*]")
        .replaceField("beneficialOwnersDetails")
    .build(), executor);
```

This pseudocode is illustrative only. The concrete implementation must preserve today's
observable behavior even if the final API shape of recursive steps differs.

### Required approach

```text
1. Add or confirm exact current behavior tests first.
2. Implement BeneficialOwnersTraversalPolicy.
3. Implement BeneficialOwnersReducer.
4. Replace manual recursion with RecursiveFetchStep + reducer.
5. Keep old implementation temporarily if needed for comparison tests.
6. Remove old implementation only after all tests pass.
```

### Public contract must stay unchanged

```text
- part name remains beneficialOwners
- response field remains beneficialOwnersDetails
- meta.parts.beneficialOwners remains stable
- empty/skipped/failure behavior remains stable
```

### Acceptance criteria

```text
Existing beneficialOwners tests pass
Depth behavior unchanged
Cycle behavior explicitly tested
Malformed nested owner payload still maps to enrichment contract violation
No response shape change
```

### Forbidden

```text
- Do not change account/owners response shape
- Do not change include semantics
- Do not change public field names
```

---

## Phase 17 — Optional Root Role Abstraction

### Goal

Reduce hardcoding of the root/main downstream in `AggregateService`.

This phase is optional. Do it only after workflow migration is stable.

Do not start this phase until:

```text
- account is migrated
- owners is migrated
- at least one multi-binding workflow test exists
- beneficialOwners migration is either completed or explicitly postponed
```

### Design rule

```text
Move away from AccountGroups-as-root if needed, but keep the root as an explicit bootstrap
role even if it is registered through the same configurator / registry as other aggregation
nodes.

Recommended model:
- one registered aggregation node is marked with role ROOT
- normal business enrichments keep role ENRICHMENT
- the execution graph is rooted at the selected ROOT node

The ROOT role still has different runtime semantics from normal enrichments:
- it is mandatory for every request
- it establishes the initial document shape
- it uses main-dependency error mapping rather than enrichment error mapping
- it is not selected through include like business enrichments
- it should not appear as a normal meta.parts business entry
- it does not run through the normal AggregationPart.execute(AggregationContext) path
```

### New abstractions

```text
AggregationNodeRole
RootSelector
RootRequestFactory
RootClient
RootResponseContract
```

The ROOT node shares registration and graph metadata with normal aggregation nodes, but its
bootstrap fetch contract remains separate from the normal enrichment execution contract.
It is registered alongside `AggregationPart` implementations rather than executed as one.

### Shape

```java
public interface RootAggregationNode {
    AggregationNodeRole role();
    String name();
    String clientName();
    Mono<JsonNode> fetch(AggregateRequest request, ClientRequestContext context);
    ObjectNode requireObject(JsonNode response);
}
```

```java
public enum AggregationNodeRole {
    ROOT,
    ENRICHMENT
}
```

### Desired flow

```text
AggregateService
  -> rootSelector.select(...)
  -> execute ROOT node fetch
  -> validate / materialize initial root document
  -> plan ENRICHMENT graph rooted from the selected ROOT node
  -> partExecutor.execute(rootNode.clientName(), root, ctx, plan)
  -> attach meta
```

### Acceptance criteria

```text
AggregateService no longer depends directly on AccountGroups
AccountGroups is registered as the current ROOT node
Graph construction can start from the selected ROOT node
External API unchanged
All tests pass
```

### Forbidden

```text
- Do not change controller API
- Do not add multiple ROOT nodes unless needed
- Do not change request DTO shape
```

---

## Phase 18 — Documentation, Test Kit, and Examples

### Goal

Make future enrichment implementation and verification easy.

### New docs

```text
docs/aggregation-workflow.md
docs/add-new-enrichment.md
error-handling-design.md update only if necessary
```

### New test support

```text
WorkflowPartTestKit
WorkflowPartFixtureBuilder
WorkflowAssertions
```

### Required content

```text
1. Part vs Binding vs Step
2. Simple keyed enrichment example
3. Multi-binding enrichment example
4. Compute enrichment example
5. Recursive traversal example
6. Soft vs fatal outcomes
7. RFC 9457 error mapping
8. Internal JSON Patch rules
9. Testing checklist for new enrichment
```

### Acceptance criteria

```text
A developer can add a simple enrichment by following the docs
A developer can verify a new workflow part through the shared test kit without bespoke
test scaffolding for the standard outcome matrix
Examples compile or are clearly marked as pseudocode
Docs match actual code
```

---

## Phase 19 — Retire Legacy Enrichment Authoring

### Goal

Make workflow the only supported authoring model for business enrichments after migration
stabilizes.

### Required changes

```text
1. Migrate the remaining legacy enrichments to workflow-based implementations.
2. Freeze new usages of AggregationEnrichment and KeyedArrayEnrichment for business parts.
3. Remove legacy authoring base classes, or move them into a clearly deprecated internal area
   only if a short-lived compatibility bridge is still required.
4. Keep only the low-level reusable services still needed by the workflow engine.
5. Update docs and examples so new enrichments are documented only through workflow.
```

### Acceptance criteria

```text
No business enrichment is authored through legacy base classes
New enrichment examples use workflow only
Public behavior unchanged
./gradlew test passes
```

### Forbidden

```text
- Do not change public response shapes during the retirement step
- Do not change the public error contract during the retirement step
- Do not keep dual authoring indefinitely without a concrete retained use case
```

---

# 8. Testing Checklist for Every New Workflow Enrichment

Every new workflow-based enrichment must have tests for:

```text
1. Happy path: enrichment is applied.
2. No keys in source: SKIPPED / NO_KEYS_IN_MAIN or a documented source-specific reason.
3. Downstream empty body: EMPTY / DOWNSTREAM_EMPTY.
4. Downstream 404: EMPTY / DOWNSTREAM_NOT_FOUND.
5. Downstream timeout: RFC 9457 dependency failure.
6. Downstream 401/403: RFC 9457 auth dependency failure.
7. Invalid downstream payload: RFC 9457 invalid payload or contract violation.
8. Patch application failure: RFC 9457 orchestration failure.
9. meta.parts entry is present and correct.
10. Metrics are emitted.
```

For multi-binding enrichments also test:

```text
1. Binding B uses keysB.
2. Binding C uses keysC.
3. Later binding can read from previous binding result.
4. One binding no-op behavior is deterministic.
5. Combined patch is applied correctly.
```

For recursive enrichments also test:

```text
1. Empty seed keys.
2. Single-level traversal.
3. Multi-level traversal.
4. Max depth reached.
5. Cycle detected.
6. Duplicate nodes deduplicated.
7. Reducer output is stable.
```

---

# 9. Handoff Template for Agents

Every agent must finish with a handoff note in the PR description or task output:

````markdown
## What changed

- ...

## Why

- ...

## Files touched

- ...

## Behavior changes

- None / describe exactly

## Tests added or updated

- ...

## How verified

```bash
./gradlew test
```

## Known risks

- ...

## Next recommended phase

- Phase N: ...
````

---

# 10. Final Definition of Done

This migration is complete when:

```text
1. New simple keyed enrichment can be added with one client and one workflow part class.
2. New multi-REST enrichment can define multiple endpoint-specific bindings.
3. Each binding can extract its own keys from root, current root, or previous step result.
4. New compute enrichment can calculate a scalar/object and write it into A.
5. Recursive traversal is reusable and beneficialOwners uses it.
6. Writes are explicit JsonPatch-like operations internally.
7. Public API response shape remains stable.
8. meta.parts remains stable.
9. RFC 9457 problem contract remains stable.
10. Documentation explains how to add new enrichments.
11. Workflow is the only supported authoring model for new business enrichments.
```

---

# 11. Recommended Execution Order

```text
[x] Phase 1  -> Characterization tests
[x] Phase 2  -> Internal JSON Patch model
[x] Phase 3  -> Patch builder helpers
[x] Phase 4  -> DownstreamBinding model
[x] Phase 5  -> Adapt existing keyed support
[ ] Phase 6  -> Workflow model skeleton
[ ] Phase 7  -> KeyedBindingStep
[ ] Phase 8  -> Migrate account
[ ] Phase 9  -> Binding metrics
[ ] Phase 10 -> Migrate owners
[ ] Phase 11 -> Multi-binding workflow
[ ] Phase 12 -> Compute step
[ ] Phase 13 -> Harden patch conflict detection and write ownership
[ ] Phase 14 -> Recursive traversal skeleton
[ ] Phase 15 -> Traversal reducer
[ ] Phase 16 -> Migrate beneficialOwners
[ ] Phase 17 -> Optional Root role abstraction
[ ] Phase 18 -> Documentation and examples
[ ] Phase 19 -> Retire legacy enrichment authoring
```

Do not skip Phase 1.

Do not start recursive traversal before at least one simple keyed enrichment is migrated successfully.

Do not introduce external workflow DSL until all Java-based workflow patterns are stable.

---

# 12. Architectural Summary for Agents

The migration should move the project from this:

```text
New enrichment = write a custom AggregationPart.execute() with extraction, REST call, merge, errors
```

to this:

```text
New enrichment = describe business part + downstream bindings + write rules
```

The engine should own:

```text
- planning
- ordering
- concurrency
- downstream failure normalization
- soft outcomes
- patch application
- metrics
- meta.parts
- RFC 9457 errors
```

The enrichment should own only:

```text
- business name
- dependencies
- endpoint-specific key selectors
- downstream client method
- response key selectors
- target write rule
- optional compute/reduce logic
```

This is the central architecture goal. Do not dilute it.
