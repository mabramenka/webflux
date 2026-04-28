# Task: Refactor aggregation pipeline so accountGroup is a mandatory base enrichment part

You are working on the repository:

https://github.com/mabramenka/webflux

Use the current `main` branch.

Also follow the engineering discipline from:

https://github.com/forrestchang/andrej-karpathy-skills/blob/main/CLAUDE.md

Important working principles from that file:
- Think before coding.
- State assumptions explicitly.
- Prefer simplicity.
- Do not add speculative abstractions.
- Make surgical changes.
- Touch only what is necessary.
- Every changed line must trace directly to this task.
- Define success criteria.
- Write/adjust tests to verify the intended behavior.
- Do not silently choose between ambiguous interpretations.

## Context

This project is a Java / Spring Boot 4 / WebFlux aggregation gateway.

The current implementation has a structural architecture problem:

`AggregateService` directly calls the account-group downstream service before invoking the aggregation part executor. This means account-group is hardcoded as a special pre-step, and only the later enrichers participate in the aggregation graph.

Current conceptual flow:

```text
AggregateService
  -> hardcoded AccountGroups client call
  -> accountGroup response becomes root JSON
  -> AggregationPartExecutor applies selected enrichers
```

This is not the intended architecture.

The intended architecture is:

```text
Aggregation starts with an empty ObjectNode {}
  -> accountGroup base enrichment creates/replaces the base JSON
  -> account enrichment overlays account data
  -> owners enrichment overlays owner data
  -> beneficialOwners enrichment overlays beneficial owner data
  -> final aggregated JSON
```

In other words:

**All response construction must happen through aggregation parts.**

The account-group call must become a normal aggregation part/enricher, not a hardcoded call in `AggregateService`.

## Desired architecture

Introduce an `accountGroup` aggregation part.

It should be a mandatory **base enrichment** part.

It is still an enricher. Do not introduce a completely separate root-part architecture unless unavoidable.

Recommended naming:

```text
AccountGroupEnrichment
```

or:

```text
AccountGroupBaseEnrichment
```

The accountGroup part should:

```text
name = "accountGroup"
criticality = REQUIRED
publicSelectable = false
base = true
dependencies = []
result = ReplaceDocument(accountGroupResponse)
```

It should:
- take request ids from aggregation request context;
- build the account-group downstream request;
- call `/account-groups`;
- require a non-empty response body;
- validate that the response is object-shaped;
- return `AggregationPartResult.ReplaceDocument(accountGroupResponse)`;
- preserve the current downstream error mapping for the main/account-group dependency.

The other enrichers should depend on it:

```text
account          -> depends on accountGroup
owners           -> depends on accountGroup
beneficialOwners -> depends on owners
```

`beneficialOwners` already depends on `owners`; keep that.

## Key design requirement

Do **not** implement hidden magic where “the first part becomes the base/root part”.

That is too implicit and order-sensitive.

Instead, make the base behavior explicit in the part contract, for example:

```java
default boolean base() {
    return false;
}

default boolean publicSelectable() {
    return true;
}
```

Then `AccountGroupEnrichment` can override:

```java
@Override
public boolean base() {
    return true;
}

@Override
public boolean publicSelectable() {
    return false;
}
```

Alternative naming is acceptable if it is clearer, but keep the model simple.

The planner should validate and use this explicit metadata.

## Planner semantics

The planner should support these rules:

```text
- exactly one base enricher exists;
- base enricher has no dependencies;
- base enricher is always included in the effective execution plan;
- base enricher is not user-selectable by public include values;
- public include cannot include non-public/internal parts;
- include = [] means base enricher only;
- include = null means base enricher + all public enrichers;
- include = ["account"] means base enricher + account;
- include = ["owners"] means base enricher + owners;
- include = ["beneficialOwners"] means base enricher + owners + beneficialOwners;
- unknown public include values still fail before any downstream call.
```

Expected execution levels:

```text
level 0: accountGroup
level 1: account, owners
level 2: beneficialOwners
```

When only `include = []`:

```text
level 0: accountGroup
```

## AggregateService target behavior

`AggregateService` must no longer know about `AccountGroups`.

Remove from `AggregateService`:
- `AccountGroups accountGroupClient`;
- hardcoded `ACCOUNT_GROUP_CLIENT_NAME`;
- direct `accountGroupClient.fetchAccountGroup(...)`;
- `DownstreamClientResponses.requireBody(...)` for account-group;
- `toAccountGroupRequest(...)`;
- account-group fields logic, unless moved into the new accountGroup part.

`AggregateService` should become an orchestration shell:

```text
1. start observation
2. plan parts from include
3. create empty ObjectNode root
4. execute the planned aggregation parts
5. attach meta if needed
6. return final JsonNode
```

The executor should receive an empty root and execute the full graph including accountGroup.

## AggregationContext / request context

Currently aggregation parts need access to the root snapshot and `ClientRequestContext`.

After this refactor, `AccountGroupEnrichment` also needs request ids.

Add request-level data to the context in the simplest way.

Acceptable options:
- add `AggregateRequest` to `AggregationContext`;
- or create a small internal `AggregationRequestContext` containing normalized ids/include and `ClientRequestContext`.

Prefer the simpler change that touches fewer files.

Do not expose transport/controller concerns deeper than necessary.

## Error semantics

Preserve the existing public error mapping for account-group/main downstream failures as much as possible.

Even though accountGroup becomes an aggregation part internally, the public error contract may still call it `main`.

That is acceptable and probably preferable.

Expected mapping should remain approximately:

```text
accountGroup timeout            -> MAIN-TIMEOUT
accountGroup unavailable        -> MAIN-UNAVAILABLE
accountGroup bad status         -> MAIN-BAD-RESPONSE
accountGroup invalid payload    -> MAIN-INVALID-PAYLOAD
accountGroup contract violation -> MAIN-CONTRACT-VIOLATION
accountGroup auth failure       -> MAIN-AUTH-FAILED
```

Do not leak raw downstream response bodies or tokens.

Do not expose downstream internal details beyond the current problem contract.

## Important business semantics to verify

The intended business semantics are:

```text
- accountGroup/base enrichment is mandatory.
- if accountGroup fails, the whole request fails.
- if accountGroup returns empty body, the whole request fails.
- if accountGroup returns non-object JSON, the whole request fails.
- include = [] returns only the accountGroup/base document.
- include = null returns accountGroup + all public enrichers.
- selected enrichers run only after accountGroup has produced the base document.
```

Do not change optional/required enrichment semantics beyond what is necessary for this structural refactor unless tests force it.

If existing tests currently assert soft outcomes for missing keys, downstream empty body, or enrichment 404, do not mix that broader behavior change into the first refactor unless required. Prefer separating:
1. structural refactor;
2. required/optional error semantics correction.

If you believe both must be changed together, explain why before implementing.

## Testing requirements

Update or add tests so the refactor is verified.

At minimum, tests should prove:

### Planner tests

```text
include = [] -> effective plan contains only accountGroup
include = null -> effective plan contains accountGroup + all public enrichers
include = ["account"] -> accountGroup + account
include = ["owners"] -> accountGroup + owners
include = ["beneficialOwners"] -> accountGroup + owners + beneficialOwners
include = ["accountGroup"] -> validation error because accountGroup is not public selectable
include = ["unknown"] -> validation error before downstream calls
exactly one base enricher is required
base enricher must not have dependencies
```

### AggregateService / executor tests

```text
AggregateService starts from empty root and does not call AccountGroups directly.
accountGroup part creates/replaces root document.
include = [] returns accountGroup response only.
include = ["account"] runs accountGroup first, then account.
account and owners can still run at the same dependency level after accountGroup.
beneficialOwners still runs after owners.
```

### Error tests

```text
empty accountGroup response -> MAIN-CONTRACT-VIOLATION or existing equivalent
non-object accountGroup response -> MAIN-CONTRACT-VIOLATION
accountGroup downstream 401/403 -> MAIN-AUTH-FAILED
accountGroup timeout -> MAIN-TIMEOUT
accountGroup invalid payload -> MAIN-INVALID-PAYLOAD
```

### Regression tests

Keep existing behavior for currently unrelated scenarios unless explicitly changed:
- request validation;
- malformed request body;
- invalid detokenize;
- traceparent problem response behavior;
- downstream header forwarding;
- account/owners/beneficialOwners enrichment behavior;
- metrics if currently asserted.

## Non-goals

Do not do these unless they are strictly necessary:

```text
- Do not switch WebFlux to MVC.
- Do not redesign the public API response envelope.
- Do not rewrite the whole workflow engine.
- Do not introduce a large new abstraction hierarchy.
- Do not refactor unrelated packages.
- Do not rename public include values except where accountGroup is explicitly internal.
- Do not change OpenAPI extensively unless required by compile/tests.
- Do not change formatting globally.
```

## Expected implementation outline

A possible safe sequence:

### Step 1 — Extend the part contract minimally

Add explicit metadata to `AggregationPart`, for example:

```java
default boolean base() {
    return false;
}

default boolean publicSelectable() {
    return true;
}
```

Keep defaults backward compatible.

### Step 2 — Add AccountGroupEnrichment

Create an aggregation part for accountGroup.

It should use:
- `AccountGroups` client;
- `DownstreamClientResponses.requireBody`;
- `HttpServiceGroups.ACCOUNT_GROUP` / current main client naming;
- current default fields logic;
- current request id normalization logic from `AggregateService.toAccountGroupRequest`.

It should return `AggregationPartResult.ReplaceDocument`.

### Step 3 — Add request data to AggregationContext

Make request ids available to `AccountGroupEnrichment`.

Prefer the smallest change that keeps existing code readable.

### Step 4 — Update planner

Planner must:
- find exactly one `base()` part;
- always include it;
- exclude it from public user selection;
- keep dependency expansion;
- validate base constraints;
- preserve include semantics.

### Step 5 — Update existing enrichers

Add dependencies:

```text
account -> accountGroup
owners -> accountGroup
```

Keep:

```text
beneficialOwners -> owners
```

### Step 6 — Update executor / AggregateService

Change execution so the executor starts with an empty root and runs all selected levels, including accountGroup.

`AggregateService` must no longer call `AccountGroups`.

### Step 7 — Update tests

Update tests first where possible, then code.

Run:

```bash
./gradlew test
./gradlew check
```

If `check` is too slow because of external tooling, at least run:

```bash
./gradlew test
./gradlew spotlessCheck
./gradlew verifyBoot4Classpath
```

If commands cannot be run, state that explicitly and explain what was statically verified.

## Acceptance criteria

The task is complete only if:

```text
- AggregateService has no direct AccountGroups dependency.
- Account-group downstream call is implemented as an aggregation part.
- Aggregation starts from an empty ObjectNode.
- accountGroup base enrichment is always part of the effective execution plan.
- accountGroup is not public-selectable via include.
- include semantics match the table in this prompt.
- account and owners depend on accountGroup.
- beneficialOwners still depends on owners.
- accountGroup failures still map to main dependency problem codes.
- Tests cover the new planner and execution behavior.
- Existing unrelated API validation/problem tests still pass.
- No broad unrelated refactor was performed.
```

## Output expected from you

When done, provide:

```text
1. concise summary of changes;
2. list of files changed;
3. explanation of how the new pipeline works;
4. tests added/updated;
5. commands run and results;
6. any risks or follow-up work.
```

Be direct. If something cannot be done cleanly, explain the tradeoff instead of hiding it.
