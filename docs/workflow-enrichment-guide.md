# Workflow Enrichment Authoring Guide

This guide documents the workflow-based enrichment style that is now used by:

- `account` (`AccountEnrichment`)
- `owners` (`OwnersEnrichment`)
- `beneficialOwners` (`BeneficialOwnersEnrichment`)

It is intentionally focused on the current `main` implementation and avoids future/optional abstractions.

## 1) Part Shape

- Current style: extend `WorkflowAggregationPart` and declare one `AggregationWorkflow`.

Minimal workflow part skeleton:

```java
@Component
final class ExampleEnrichment extends WorkflowAggregationPart {

    ExampleEnrichment(WorkflowExecutor executor) {
        super(
                new AggregationWorkflow(
                        "example",
                        Set.of("owners"),
                        PartCriticality.REQUIRED,
                        List.of(/* workflow steps */),
                        WriteOwnership.of("exampleField")),
                executor);
    }
}
```

`WorkflowAggregationPart` delegates execution to `WorkflowExecutor`; the public part contract (`name`, dependencies, criticality, `meta.parts`) stays unchanged.

## 2) Workflow Context Sources

Use these sources deliberately:

- `ROOT_SNAPSHOT`
  - immutable snapshot visible at workflow start.
  - use for deterministic key extraction from part input.
- `CURRENT_ROOT`
  - workflow-local mutable document; prior step patches are already applied.
  - use only when later key extraction must see writes from earlier steps.
- `STEP_RESULT`
  - named value stored by an earlier step (`StepResult.stored(...)`).
  - use for pipelines where one step computes/fetches and another step consumes the result.

Do not mutate `ROOT_SNAPSHOT` directly.

## 3) Step Types and When to Use Them

- `KeyedBindingStep`
  - keyed downstream fetch + indexed match + patch write.
  - supports `ROOT_SNAPSHOT`, `CURRENT_ROOT`, `STEP_RESULT` (fetch-only for `STEP_RESULT`).
- `ComputeStep`
  - pure in-process transformation.
  - stores a `JsonNode` to workflow variables; does not call downstream.
- `RecursiveFetchStep`
  - grouped recursive traversal; stores traversal output JSON as `STEP_RESULT`.
  - no patch write from this step.
- `TraversalReducerStep`
  - reads traversal `STEP_RESULT`, calls reducer, returns `StepResult.applied(patch)`.

## 4) Write Ownership

Declare `WriteOwnership` on workflow parts to guard root writes at construction time.

Current production declarations:

- `account` -> `WriteOwnership.of("account1")`
- `owners` -> `WriteOwnership.of("owners1")`
- `beneficialOwners` -> `WriteOwnership.of("beneficialOwnersDetails")`

If a step writes a root field not owned by the part, workflow definition validation must fail.

## 5) Outcomes and Error Boundaries

Step outcomes:

- `StepResult.applied(patch)` -> workflow continues
- `StepResult.stored(name, value)` -> workflow continues
- `StepResult.skipped(reason)` -> workflow short-circuits to part `SKIPPED`
- `StepResult.empty(reason)` -> workflow short-circuits to part `EMPTY`

Error boundaries:

- Downstream/contract errors should map to existing facade error types (`FacadeException` subclasses).
- `WorkflowExecutor` owns patch conflict/apply orchestration and wraps merge/invariant failures as orchestration errors.
- Keep public RFC 9457/API contracts unchanged from business parts.

## 6) Production Examples

### 6.1 Simple keyed part (account style)

`AccountEnrichment` declares one `KeyedBindingStep`:

- extract keys from `ROOT_SNAPSHOT` via `$.data[*]` + `accounts[*].id`
- call `Accounts.fetchAccounts(...)`
- index response by `id`
- append matched items to `account1`

### 6.2 Multi-key fallback part (owners style)

`OwnersEnrichment` declares one `KeyedBindingStep` with fallback paths:

- key extraction paths:
  - `basicDetails.owners[*].id`
  - `basicDetails.owners[*].number`
- response index paths:
  - `individual.number`
  - `id`
- append matched items to `owners1`

### 6.3 Multi-binding workflow pattern

Current `main` supports multi-step workflows where later steps can read `CURRENT_ROOT`.

Illustrative snippet (documentation-only):

```java
new AggregationWorkflow(
        "twoStepExample",
        Set.of(),
        PartCriticality.REQUIRED,
        List.of(
                new KeyedBindingStep("fetchPrimary", primaryBindingFromRootSnapshot()),
                new KeyedBindingStep("fetchDependent", dependentBindingFromCurrentRoot())),
        WriteOwnership.of("primaryField", "dependentField"));
```

Use this shape when step 2 keys depend on writes produced by step 1.

### 6.4 Recursive part (beneficialOwners style)

`BeneficialOwnersEnrichment` declares a small workflow pipeline:

1. `RequireRootEntityOwnersStep`
   - reads `ROOT_SNAPSHOT`
   - returns `SKIPPED / NO_KEYS_IN_MAIN` when no root entity owners exist
2. `BeneficialOwnersRecursiveFetcher`
   - lower-level recursive algorithm service called by the enrichment workflow adapter
   - builds grouped seeds (`TraversalSeedGroup`) with target metadata (`dataIndex`, `ownerIndex`)
   - returns traversal results for the adapter to store as `beneficialOwnersTraversal`
3. `TraversalReducerStep`
   - reads `beneficialOwnersTraversal`
   - applies `BeneficialOwnersTraversalReducer` to write
     `/data/{dataIndex}/owners1/{ownerIndex}/beneficialOwnersDetails`

## 7) Test Authoring Pattern

Use focused tests per layer:

- step behavior:
  - `KeyedBindingStepTest`
  - `RecursiveFetchStepTest`
  - `TraversalReducerStepTest`
  - `RecursiveTraversalEngineTest`
- workflow orchestration:
  - `WorkflowExecutorTest`
- business parity:
  - `OwnersEnrichmentWorkflowTest`
  - `BeneficialOwnersEnrichmentTest`
- service-level dependency wiring:
  - `AggregateServiceDependencyTest`

For patch-verifying part tests, follow `BeneficialOwnersEnrichmentTest`:

1. call `part.execute(context)`
2. if result is `AggregationPartResult.JsonPatch`, apply it with `JsonPatchApplicator`
3. assert mutated local root JSON
4. assert downstream interactions and soft outcomes

## 8) Navigation Tips (fff-first)

Preferred repository search commands:

- `fffind` for file/path/class search
- `ffgrep` for content search
- `fff-multi-grep` for related symbols

If fff tools are unavailable in your shell, use `rg` as fallback and keep searches narrow.

## 9) What Not To Do

- Do not introduce `TRAVERSAL_STATE` support.
- Do not change `WorkflowVariableStore` shape.
- Do not add YAML/JSON DSL or JSONPath predicates.
- Do not move business reducer logic into shared recursive runtime.
- Do not mutate global root from workflow steps.
- Do not change public response/meta.parts/RFC 9457 contracts as part of authoring refactors.
