# Architecture

## Scope

Aggregation Gateway is a synchronous domain facade, not an owner of account, account-group, or owner data. Its boundary is response composition: validate the caller request, build an effective aggregation plan, run the mandatory base part and selected public enrichment parts, merge successful results, and return one JSON document.

The service deliberately works with Jackson `JsonNode` / `ObjectNode` because downstream payloads remain dynamic. That flexibility is part of the design, but the public error and metadata contracts are facade-owned and stable.

## Module Boundaries

| Package | Responsibility | Owns |
| --- | --- | --- |
| `api` | HTTP controllers and transport DTOs | Public route versioning and request shape |
| `service` | Request orchestration entry point | Observation, planning handoff, empty-root creation, success metadata attachment |
| `part` | Part graph planning and execution | Base-part inclusion, dependency expansion, level execution, result application, part metrics |
| `model` | Shared domain contracts | `AggregationPart`, context, result, selection, outcomes, request context |
| `enrichment.<name>` | Business-specific aggregation parts | Part names, dependencies, downstream requests, workflow definitions, merge targets |
| `workflow` | Workflow implementation model | Workflow execution, variable flow, step results, validation |
| `workflow.path` | Project-specific path dialect | Key extraction and response indexing paths |
| `patch` | Internal JSON write model | Patch documents, pointers, write options, patch application |
| `client` | HTTP service clients and downstream filter | Outbound routes, forwarded context, downstream failure normalization |
| `error` | Problem Detail mapping | Stable catalog, categories, retryability, validation errors |
| `config` | WebFlux, clients, SSL, MDC, context propagation, OpenAPI | Runtime and framework integration |

Architectural rules:

- `AggregationPart` is the business-level aggregation unit planned and executed by the part graph.
- `accountGroup` is the single mandatory base part. It is internal and not public-selectable.
- Workflow is an implementation style for an `AggregationPart`, not a separate public API.
- `WorkflowStep` is a technical operation inside a workflow-based part.
- Patch documents are internal JSON write instructions; they are not the public HTTP API.
- `workflow.path` is intentionally smaller than JSONPath and supports only the patterns used by current enrichments.

## Runtime Flow

1. `api` validates the inbound POST body or GET path/query values.
2. `config` builds `ClientRequestContext` from selected inbound headers and query parameters.
3. `service` asks `part` to plan the requested `include` set before any downstream call, so unknown public part names fail early.
4. The planner expands public dependencies, includes the mandatory base `accountGroup` part, and groups selected parts by dependency level.
5. `service` creates an empty `ObjectNode` root and passes it to the executor.
6. Level 0 runs `accountGroup`; it calls `/account-groups` and replaces the empty root with the object-shaped account-group response.
7. Later levels run public enrichment parts. Parts in the same level run concurrently against the same immutable root snapshot.
8. The executor applies same-level results to the mutable root in stable graph order before the next level starts.
9. `service` attaches public `meta.parts` outcomes when there are public part outcomes and returns the final root.

Default execution levels:

1. `accountGroup`
2. `account`, `owners`
3. `beneficialOwners`

## Include Semantics

| Request | Effective selection |
| --- | --- |
| `include == null` | base part plus every public part |
| `include == []` | base part only |
| `include == ["account"]` | base part plus `account` |
| `include == ["owners"]` | base part plus `owners` |
| `include == ["beneficialOwners"]` | base part plus `owners` and `beneficialOwners` |

Public `include` values select public enrichment parts only. `accountGroup` is included automatically and cannot be requested directly.

## Consistency And Failure Semantics

- `accountGroup` is mandatory. Timeout, unavailable dependency, auth failure, bad status, invalid payload, empty body, or non-object JSON fail the request.
- Main dependency failures keep the public `main` problem category even though the call is implemented as the internal `accountGroup` part.
- Explicitly selected transitive dependencies are enabled automatically.
- Keyed enrichments request every distinct key found in the current root snapshot and attach only entries that are returned and matched.
- Same-level part results are generated from the same root snapshot, then merged into the mutable root in graph order.
- The service does not persist state and has no distributed transaction. Consistency is per request and depends on downstream responses at request time.
- If the request fails, the response body is only a `ProblemDetail`; partial aggregate data and partial `meta.parts` are not returned.

Current success-side public outcomes:

| Outcome | Meaning |
| --- | --- |
| `APPLIED` | The part produced and applied a replacement document, merge patch, or JSON patch. |
| `EMPTY` | The part ran but semantically produced no enrichment data. |
| `SKIPPED` | The part did not call its downstream or did not run because prerequisites were not satisfied. |
| `FAILED` | An optional public enrichment had a downstream dependency failure that was downgraded by policy. |

`REQUIRED` is the default criticality. `OPTIONAL` is opt-in per part/workflow. Optional criticality only downgrades selected `DownstreamClientException` failures; merge, patch, workflow-definition, invariant, and platform failures remain fatal.

## Downstream Services

| Group | Interface | Method | Path |
| --- | --- | --- | --- |
| `account-group` | `AccountGroups` | `POST` | `/account-groups` |
| `account` | `Accounts` | `POST` | `/accounts` |
| `owners` | `Owners` | `POST` | `/owners` |

All downstream clients send and accept JSON. Downstream 4xx/5xx responses, transport failures, timeout-like failures, and unreadable payloads are normalized into facade-owned problem categories.

Default projection fields:

| Downstream | Default `fields` |
| --- | --- |
| `account-group` | `id,status,name,accounts,owners1` |
| `account` | `id,number,status,balance` |
| `owners` | `id,number,type,name,principalOwners,indirectOwners` |

The public `fields` query parameter currently overrides only the account-group projection. Account and owners enrichments use their own fixed defaults.

## Aggregation Parts

### accountGroup

The internal base part:

- reads request ids from `AggregationContext`;
- upper-cases ids before the downstream request;
- calls `/account-groups`;
- requires a non-empty response body;
- requires the response body to be an object;
- returns a replacement document result;
- is not emitted under `meta.parts`.

### account

Reads account ids from:

```text
$.data[*].accounts[*].id
```

Calls `/accounts` with:

```json
{
  "ids": ["acc-a", "acc-b"]
}
```

Indexes account response entries by:

```text
$.data[*].id
```

Appends matched entries to each owning `data[*]` item under:

```text
account1
```

### owners

Reads owner ids from the account-group response using fallback fields:

```text
$.data[*].basicDetails.owners[*].id
$.data[*].basicDetails.owners[*].number
```

Calls `/owners` with:

```json
{
  "ids": ["owner-a", "owner-b"]
}
```

Indexes owner response entries using fallback fields:

```text
$.data[*].individual.number
$.data[*].id
```

Appends matched entries to each owning `data[*]` item under:

```text
owners1
```

Current matching is stricter than extraction/indexing: the write rule matches `basicDetails.owners[*].id` to `individual.number`. The review notes track this as a follow-up because number-only source data may fetch owners without attaching them.

### beneficialOwners

Resolves the ownership tree rooted at entity owners already merged under `owners1`.

For every `data[*]` item, the part walks each entity owner and repeatedly fetches `/owners` in level-by-level batches. Individuals encountered anywhere in the tree are collected, deduplicated by number in first-seen order, and attached under:

```text
beneficialOwnersDetails
```

Traversal is bounded to 6 levels. The part depends on `owners`, so selecting `beneficialOwners` also selects `owners`.

## Workflow Path Dialect

Workflow binding support uses JSONPath-like syntax, not a full JSONPath engine:

```text
$
$.field
$.field[*]
$.field[*].nested.field
```

Filters, slices, explicit numeric indexes, bracket notation, and recursive descent are not supported.

For authoring details, see [workflow-enrichment-guide.md](workflow-enrichment-guide.md).

## Operations

Default exposed Actuator endpoints:

- `/actuator/health`
- `/actuator/health/liveness`
- `/actuator/health/readiness`
- `/actuator/info`
- `/actuator/metrics`

The Java compile tasks fail on deprecation and removal warnings, keeping major-version migration issues visible during normal builds. The `check` task also runs the Boot 4 / Spring Framework 7 / Jackson 3 classpath guard.
