# Error Handling Design

**Stack:** Spring Boot 4 / Spring Framework 7
**External error contract:** RFC 9457 Problem Details for HTTP APIs
**Status:** Current source of truth for request-level errors and success-side part outcomes.

Related docs:

- [Architecture](architecture.md)
- [Workflow enrichment guide](workflow-enrichment-guide.md)
- [Architecture review notes](architecture-review.md)

## Purpose and Scope

This service is an aggregation facade. For every aggregate request it validates the caller input, runs a mandatory base part that calls the account-group downstream, executes selected public enrichment parts by dependency level, and merges successful part results into one JSON response.

This document defines:

- how successful part execution is reported in `meta.parts`;
- when a failure aborts the whole request with RFC 9457 `ProblemDetail`;
- the public problem fields, categories, error codes, and retryability contract;
- the boundary between main dependency failures, enrichment dependency failures, orchestration errors, and platform errors;
- information-hiding rules for all public responses.

This document does not define business validation rules beyond the public request contract, retry/circuit-breaker tuning, authentication mechanisms, timeout values, or observability backend configuration.

## What Was Outdated

The previous version documented only `APPLIED`, `EMPTY`, and `SKIPPED` as success-side outcomes. Current README, model enums, executor code, and tests also support `FAILED` for non-fatal optional enrichment dependency failures.

The previous version also treated all enrichment infrastructure failures as request-fatal. Current behavior is more precise: downstream dependency failures are fatal for `REQUIRED` parts, but an `OPTIONAL` part may continue the request when the thrown failure is a `DownstreamClientException`. That failure is recorded in `meta.parts.<part>` as `FAILED` with `criticality`, `reason`, and `errorCode`.

The previous document also did not consistently describe `REQUIRED` versus `OPTIONAL` criticality and did not state that orchestration, merge, patch, workflow-definition, and invariant failures remain fatal even for optional parts.

## Core Principles

1. **The main dependency is mandatory.** The base `accountGroup` part is not public-selectable and must succeed before any enrichment can produce useful output. Main timeout, auth failure, 5xx, invalid payload, empty body, non-object body, transport failure, and raw unexpected status are request-level failures.
2. **Success-side part outcomes are not request errors.** `APPLIED`, `EMPTY`, `SKIPPED`, and `FAILED` appear only in successful responses under `meta.parts`. They are not RFC 9457 problems.
3. **Criticality only changes downstream dependency failure handling.** `REQUIRED` enrichment dependency failures abort the request. `OPTIONAL` enrichment dependency failures caused by `DownstreamClientException` are recorded as `FAILED` and the request continues.
4. **Criticality does not make orchestration unsafe states safe.** Merge failures, patch failures, invariant violations, workflow-definition errors, invalid internal state, wrong part results, empty part publishers, and unclassified internal exceptions abort the request regardless of `REQUIRED` or `OPTIONAL`.
5. **Soft data absence is not a technical failure.** Missing keys, unsupported context, empty dependency results, downstream 404 where handled by the enrichment step, and empty enrichment responses where semantically allowed produce `SKIPPED` or `EMPTY`.
6. **Error responses contain no partial aggregate.** If the request fails, the response body is only a `ProblemDetail`. No partial root JSON and no partial `meta.parts` are returned.
7. **Partial part metadata exists only on success.** Successful responses may include `meta.parts` entries that disclose applied, empty, skipped, or failed optional enrichment state.
8. **The facade owns the external contract.** Downstream problem bodies, hostnames, raw bodies, headers, exception names, stack traces, and implementation details are never proxied to clients.
9. **Retryability is explicit.** Clients use the `retryable` extension, not only the HTTP status, to decide whether retrying is appropriate.

## Success-Side Part Outcomes

`meta.parts` is attached by `AggregateService` only when there are public-selectable effective parts with outcomes. The base `accountGroup` part participates in execution but is not public-selectable, so it is not emitted in `meta.parts`.

Expected shape:

```json
{
  "meta": {
    "parts": {
      "account": {
        "status": "FAILED",
        "criticality": "OPTIONAL",
        "reason": "TIMEOUT",
        "errorCode": "ENRICH-TIMEOUT"
      }
    }
  }
}
```

Each part entry has:

| Field | Required | Values | Notes |
| --- | --- | --- | --- |
| `status` | Always | `APPLIED`, `EMPTY`, `SKIPPED`, `FAILED` | From `PartOutcomeStatus`. |
| `criticality` | Always | `REQUIRED`, `OPTIONAL` | From the part or workflow definition. Default is `REQUIRED`. |
| `reason` | Non-`APPLIED` only | `PartOutcomeReason` | Required for `EMPTY`, `SKIPPED`, and `FAILED`; absent for `APPLIED`. |
| `errorCode` | `FAILED` only | `ENRICH-*` catalog code | Present only for optional downstream dependency failures that were converted to `FAILED`. |

### APPLIED

Used when the part produced a replacement document, merge patch, or JSON patch and the result was successfully applied to the root document.

- Technical failure: no.
- Can appear for `REQUIRED`: yes.
- Can appear for `OPTIONAL`: yes.
- Fields: `status`, `criticality`.
- Must not include `reason` or `errorCode`.

Example:

```json
{
  "status": "APPLIED",
  "criticality": "REQUIRED"
}
```

### EMPTY

Used when the part ran but semantically produced no enrichment data. This is data absence, not a technical failure.

Current reasons:

- `DOWNSTREAM_EMPTY`: downstream returned no body or the response could not produce any matched data where empty is allowed.
- `DOWNSTREAM_NOT_FOUND`: downstream returned 404 and the enrichment step explicitly treats that as "no data for these keys."

- Technical failure: no.
- Can appear for `REQUIRED`: yes.
- Can appear for `OPTIONAL`: yes.
- Fields: `status`, `criticality`, `reason`.
- Must not include `errorCode`.

Example:

```json
{
  "status": "EMPTY",
  "criticality": "REQUIRED",
  "reason": "DOWNSTREAM_NOT_FOUND"
}
```

### SKIPPED

Used when the part did not call its downstream or did not run because prerequisites were not satisfied.

Current reasons:

- `NO_KEYS_IN_MAIN`: the selected key paths yielded no keys from the root/source document.
- `UNSUPPORTED_CONTEXT`: `supports(context)` returned false.
- `DEPENDENCY_EMPTY`: at least one declared dependency did not apply successfully, so the dependent part is not runnable.

- Technical failure: no.
- Can appear for `REQUIRED`: yes.
- Can appear for `OPTIONAL`: yes.
- Fields: `status`, `criticality`, `reason`.
- Must not include `errorCode`.

Example:

```json
{
  "status": "SKIPPED",
  "criticality": "REQUIRED",
  "reason": "NO_KEYS_IN_MAIN"
}
```

### FAILED

Used only when an `OPTIONAL` public enrichment part throws a `DownstreamClientException` and the failure policy chooses to continue the request. It records a real downstream dependency failure without converting the whole response to an error.

Current failure reasons are derived from the enrichment catalog entry:

- `TIMEOUT` from `ENRICH-TIMEOUT`
- `UNAVAILABLE` from `ENRICH-UNAVAILABLE`
- `BAD_RESPONSE` from `ENRICH-BAD-RESPONSE`
- `INVALID_PAYLOAD` from `ENRICH-INVALID-PAYLOAD`
- `AUTH_FAILED` from `ENRICH-AUTH-FAILED`
- `CONTRACT_VIOLATION` from `ENRICH-CONTRACT-VIOLATION` when represented as `DownstreamClientException`
- `INTERNAL` for any unexpected catalog mapping

- Technical failure: yes, but non-fatal because the part is `OPTIONAL`.
- Can appear for `REQUIRED`: no. The same downstream failure is fatal for a required part.
- Can appear for `OPTIONAL`: yes.
- Fields: `status`, `criticality`, `reason`, `errorCode`.

Example:

```json
{
  "status": "FAILED",
  "criticality": "OPTIONAL",
  "reason": "TIMEOUT",
  "errorCode": "ENRICH-TIMEOUT"
}
```

## Part Criticality

### REQUIRED

`REQUIRED` is the default for `AggregationPart` and `AggregationWorkflow`.

For required public enrichment parts:

- `APPLIED`, `EMPTY`, and `SKIPPED` can appear in successful responses.
- Downstream dependency failures abort the request with `ENRICHMENT_DEPENDENCY` `ProblemDetail`.
- Orchestration, merge, patch, invariant, workflow-definition, and internal consistency failures abort the request.

The base main part is also effectively required, but it is not emitted in `meta.parts`.

### OPTIONAL

`OPTIONAL` is opt-in per part or workflow.

For optional public enrichment parts:

- `APPLIED`, `EMPTY`, `SKIPPED`, and `FAILED` can appear in successful responses.
- `DownstreamClientException` failures are converted to `FAILED` outcomes.
- Non-downstream failures still abort the request.

Criticality is not a general "best effort" switch. It only controls selected downstream dependency failures. It does not suppress broken merge logic, invalid workflow definitions, patch conflicts, invariant violations, or platform failures.

## Decision Matrix

| Scenario | Request result | Part outcome | Category | errorCode | Retryable | Notes |
| --- | --- | --- | --- | --- | --- | --- |
| Invalid client request body, field, path, or query | ProblemDetail | n/a | `CLIENT_REQUEST` | `CLIENT-INVALID-BODY` or `CLIENT-VALIDATION` | false | Malformed body uses `CLIENT-INVALID-BODY`; validation and unknown include use `CLIENT-VALIDATION`. |
| Unknown include part | ProblemDetail | n/a | `CLIENT_REQUEST` | `CLIENT-VALIDATION` | false | Fails before the account-group call. |
| Main timeout | ProblemDetail | n/a | `MAIN_DEPENDENCY` | `MAIN-TIMEOUT` | true | Includes `dependency: "main"`. |
| Main auth failure | ProblemDetail | n/a | `MAIN_DEPENDENCY` | `MAIN-AUTH-FAILED` | false | Downstream 401/403 from main. |
| Main 5xx or unexpected status | ProblemDetail | n/a | `MAIN_DEPENDENCY` | `MAIN-BAD-RESPONSE` or `MAIN-UNAVAILABLE` | false for bad response; true for unavailable | Downstream 503 maps to `MAIN-UNAVAILABLE`; other unexpected statuses map to `MAIN-BAD-RESPONSE`. |
| Main invalid payload | ProblemDetail | n/a | `MAIN_DEPENDENCY` | `MAIN-INVALID-PAYLOAD` | false | Decode/read failure. |
| Main empty body | ProblemDetail | n/a | `MAIN_DEPENDENCY` | `MAIN-CONTRACT-VIOLATION` | false | The base part requires a body. |
| Main non-object body | ProblemDetail | n/a | `MAIN_DEPENDENCY` | `MAIN-CONTRACT-VIOLATION` | false | The aggregate root must be an object. |
| Main domain not found | ProblemDetail | n/a | `CLIENT_REQUEST` | `CLIENT-NOT-FOUND` | false | Only when facade code explicitly classifies the condition as domain not found. A raw downstream 404 from main is not enough. |
| Raw downstream 404 from main | ProblemDetail | n/a | `MAIN_DEPENDENCY` | `MAIN-BAD-RESPONSE` | false | Main 404 is opaque unless classified as a domain outcome. |
| No enrichment keys found | 200 | `SKIPPED` | n/a | n/a | n/a | `reason: "NO_KEYS_IN_MAIN"`. |
| Dependency part is empty, skipped, or failed optional | 200 | `SKIPPED` for dependent | n/a | n/a | n/a | `reason: "DEPENDENCY_EMPTY"`; dependent downstream is not called. |
| Enrichment 404 where step handles it | 200 | `EMPTY` | n/a | n/a | n/a | `reason: "DOWNSTREAM_NOT_FOUND"`. |
| Enrichment empty body where allowed | 200 | `EMPTY` | n/a | n/a | n/a | `reason: "DOWNSTREAM_EMPTY"`. |
| Required enrichment timeout | ProblemDetail | n/a | `ENRICHMENT_DEPENDENCY` | `ENRICH-TIMEOUT` | true | Includes logical `dependency` and part metadata. |
| Optional enrichment timeout | 200 | `FAILED` | n/a | `ENRICH-TIMEOUT` in `meta.parts` | n/a | `reason: "TIMEOUT"`; no `ProblemDetail`. |
| Required enrichment auth failure | ProblemDetail | n/a | `ENRICHMENT_DEPENDENCY` | `ENRICH-AUTH-FAILED` | false | Downstream 401/403. |
| Optional enrichment auth failure | 200 | `FAILED` | n/a | `ENRICH-AUTH-FAILED` in `meta.parts` | n/a | `reason: "AUTH_FAILED"`. |
| Required enrichment 5xx or unexpected status | ProblemDetail | n/a | `ENRICHMENT_DEPENDENCY` | `ENRICH-BAD-RESPONSE` or `ENRICH-UNAVAILABLE` | false for bad response; true for unavailable | Downstream 503 maps to unavailable. |
| Optional enrichment 5xx or unexpected status | 200 | `FAILED` | n/a | `ENRICH-BAD-RESPONSE` or `ENRICH-UNAVAILABLE` in `meta.parts` | n/a | `reason: "BAD_RESPONSE"` or `UNAVAILABLE`. |
| Required enrichment invalid payload | ProblemDetail | n/a | `ENRICHMENT_DEPENDENCY` | `ENRICH-INVALID-PAYLOAD` | false | Decode/read failure. |
| Optional enrichment invalid payload | 200 | `FAILED` | n/a | `ENRICH-INVALID-PAYLOAD` in `meta.parts` | n/a | `reason: "INVALID_PAYLOAD"`. |
| Enrichment contract violation represented as `EnrichmentDependencyException` | ProblemDetail | n/a | `ENRICHMENT_DEPENDENCY` | `ENRICH-CONTRACT-VIOLATION` | false | Fatal even if caused inside an optional part because it is not a `DownstreamClientException`. |
| Optional enrichment contract violation represented as `DownstreamClientException` | 200 | `FAILED` | n/a | `ENRICH-CONTRACT-VIOLATION` in `meta.parts` | n/a | Applies only to downstream-client contract failures handled by the failure policy. |
| Patch build failure | ProblemDetail | n/a | `ORCHESTRATION` | `ORCH-MERGE-FAILED` or `ORCH-INVARIANT-VIOLATED` | false | Fatal for all parts. |
| Merge conflict or invariant violation | ProblemDetail | n/a | `ORCHESTRATION` | `ORCH-MERGE-FAILED` or `ORCH-INVARIANT-VIOLATED` | false | Fatal for all parts. |
| Invalid workflow definition detected after startup | ProblemDetail | n/a | `ORCHESTRATION` | `ORCH-CONFIG-INVALID` or `ORCH-INVARIANT-VIOLATED` | false | Bean-creation failures may prevent startup before an HTTP response exists. |
| Unexpected platform/internal exception | ProblemDetail | n/a | `PLATFORM` | `PLATFORM-INTERNAL` | false | Last-resort handler. |
| Load shedding or rejected execution | ProblemDetail | n/a | `PLATFORM` | `PLATFORM-OVERLOADED` | true | Current handler emits `Retry-After: 1`. |

## Request-Level ProblemDetail Contract

All request-level failures return `application/problem+json` and use RFC 9457 `ProblemDetail` with facade-owned extensions.

Core fields:

| Field | Public | Meaning |
| --- | --- | --- |
| `type` | Yes | Stable relative URI from `ProblemCatalog`, for example `/problems/main/timeout`. |
| `title` | Yes | Stable human-readable title from `ProblemCatalog`. |
| `status` | Yes | HTTP status code and body status. |
| `detail` | Yes | Stable generic detail from `ProblemCatalog.defaultDetail()`. |
| `instance` | Yes | Relative request instance URI, currently `/requests/{traceId-or-request-id}`. |

Extension fields:

| Field | Public | When present | Meaning |
| --- | --- | --- | --- |
| `errorCode` | Yes | Always | Stable machine-readable code. |
| `category` | Yes | Always | One of the problem categories. |
| `traceId` | Yes | Always | Trace id used for support lookup. |
| `retryable` | Yes | Always | Whether the same request can reasonably be retried. |
| `timestamp` | Yes | Always | UTC timestamp when the facade emits the response. |
| `dependency` | Yes | Main/enrichment dependency errors | Logical dependency only, such as `main`, `enricher:account`, or `enricher:owners`. |
| `violations` | Yes | Validation errors | Array of `{ "pointer": "...", "message": "..." }`. |
| `part` | Yes | Part-level fatal failures | Public part name when failure policy enriches the problem. |
| `criticality` | Yes | Part-level fatal failures | `REQUIRED` or `OPTIONAL` for the failing part. |

The following details are not public and must not appear in responses: raw downstream body, downstream URL, hostname, IP, port, raw headers, tokens, cookies, stack trace, exception class name, Spring bean name, internal class name, internal configuration key, or implementation-specific exception message.

Every success and error response should carry a valid W3C `traceparent` header. Valid inbound `traceparent` is echoed; otherwise the service generates one.

## Problem Catalog

Categories:

- `CLIENT_REQUEST`: malformed request, validation failure, authentication/authorization framework status, content negotiation failure, rate limit, or explicitly classified domain not-found.
- `MAIN_DEPENDENCY`: mandatory account-group dependency failure.
- `ENRICHMENT_DEPENDENCY`: required enrichment dependency failure or fatal enrichment contract violation.
- `ORCHESTRATION`: facade logic failure, merge failure, mapping failure, invariant violation, or runtime configuration failure.
- `PLATFORM`: unclassified internal exception or overload/load shedding.

Catalog entries aligned with `ProblemCatalog`:

| errorCode | type | title | HTTP | category | retryable |
| --- | --- | --- | --- | --- | --- |
| `CLIENT-INVALID-BODY` | `/problems/invalid-request-body` | Request body is invalid | 400 | `CLIENT_REQUEST` | false |
| `CLIENT-VALIDATION` | `/problems/validation` | Request validation failed | 400 | `CLIENT_REQUEST` | false |
| `CLIENT-UNAUTHENTICATED` | `/problems/unauthenticated` | Authentication required | 401 | `CLIENT_REQUEST` | false |
| `CLIENT-FORBIDDEN` | `/problems/forbidden` | Access denied | 403 | `CLIENT_REQUEST` | false |
| `CLIENT-NOT-FOUND` | `/problems/not-found` | Resource not found | 404 | `CLIENT_REQUEST` | false |
| `CLIENT-METHOD-NOT-ALLOWED` | `/problems/method-not-allowed` | Method not allowed | 405 | `CLIENT_REQUEST` | false |
| `CLIENT-NOT-ACCEPTABLE` | `/problems/not-acceptable` | Not acceptable | 406 | `CLIENT_REQUEST` | false |
| `CLIENT-UNSUPPORTED-MEDIA` | `/problems/unsupported-media` | Unsupported media type | 415 | `CLIENT_REQUEST` | false |
| `CLIENT-RATE-LIMITED` | `/problems/rate-limited` | Rate limit exceeded | 429 | `CLIENT_REQUEST` | true |
| `MAIN-TIMEOUT` | `/problems/main/timeout` | Main dependency timed out | 504 | `MAIN_DEPENDENCY` | true |
| `MAIN-UNAVAILABLE` | `/problems/main/unavailable` | Main dependency unavailable | 504 | `MAIN_DEPENDENCY` | true |
| `MAIN-BAD-RESPONSE` | `/problems/main/bad-response` | Main dependency returned an unexpected status | 502 | `MAIN_DEPENDENCY` | false |
| `MAIN-INVALID-PAYLOAD` | `/problems/main/invalid-payload` | Main dependency returned an invalid payload | 502 | `MAIN_DEPENDENCY` | false |
| `MAIN-CONTRACT-VIOLATION` | `/problems/main/contract-violation` | Main dependency payload violates contract | 502 | `MAIN_DEPENDENCY` | false |
| `MAIN-AUTH-FAILED` | `/problems/main/auth-failed` | Main dependency refused authentication | 502 | `MAIN_DEPENDENCY` | false |
| `ENRICH-TIMEOUT` | `/problems/enrichment/timeout` | Enrichment dependency timed out | 504 | `ENRICHMENT_DEPENDENCY` | true |
| `ENRICH-UNAVAILABLE` | `/problems/enrichment/unavailable` | Enrichment dependency unavailable | 504 | `ENRICHMENT_DEPENDENCY` | true |
| `ENRICH-BAD-RESPONSE` | `/problems/enrichment/bad-response` | Enrichment dependency returned an unexpected status | 502 | `ENRICHMENT_DEPENDENCY` | false |
| `ENRICH-INVALID-PAYLOAD` | `/problems/enrichment/invalid-payload` | Enrichment dependency returned an invalid payload | 502 | `ENRICHMENT_DEPENDENCY` | false |
| `ENRICH-CONTRACT-VIOLATION` | `/problems/enrichment/contract-violation` | Enrichment dependency payload violates contract | 502 | `ENRICHMENT_DEPENDENCY` | false |
| `ENRICH-AUTH-FAILED` | `/problems/enrichment/auth-failed` | Enrichment dependency refused authentication | 502 | `ENRICHMENT_DEPENDENCY` | false |
| `ORCH-MERGE-FAILED` | `/problems/orchestration/merge-failed` | Aggregation failed | 500 | `ORCHESTRATION` | false |
| `ORCH-MAPPING-FAILED` | `/problems/orchestration/mapping-failed` | Response mapping failed | 500 | `ORCHESTRATION` | false |
| `ORCH-INVARIANT-VIOLATED` | `/problems/orchestration/invariant` | Internal invariant violated | 500 | `ORCHESTRATION` | false |
| `ORCH-CONFIG-INVALID` | `/problems/orchestration/config` | Internal configuration error | 500 | `ORCHESTRATION` | false |
| `PLATFORM-INTERNAL` | `/problems/platform/internal` | Internal server error | 500 | `PLATFORM` | false |
| `PLATFORM-OVERLOADED` | `/problems/platform/overloaded` | Service overloaded | 503 | `PLATFORM` | true |

## Main vs Orchestration Boundary

| Situation | Classification |
| --- | --- |
| HTTP call to account-group times out, is unavailable, fails auth, or returns error status | `MAIN_DEPENDENCY` |
| Account-group response is empty | `MAIN_DEPENDENCY` / `MAIN-CONTRACT-VIOLATION` |
| Account-group response cannot be decoded | `MAIN_DEPENDENCY` / `MAIN-INVALID-PAYLOAD` |
| Account-group response decodes but root is not an object | `MAIN_DEPENDENCY` / `MAIN-CONTRACT-VIOLATION` |
| Raw account-group 404 without facade domain classification | `MAIN_DEPENDENCY` / `MAIN-BAD-RESPONSE` |
| Facade explicitly classifies requested domain object as not found | `CLIENT_REQUEST` / `CLIENT-NOT-FOUND` |
| Root object has no keys needed by an enrichment | Success-side `SKIPPED` / `NO_KEYS_IN_MAIN` |
| Facade mapping, merge, patch, dependency graph, or invariant logic fails | `ORCHESTRATION` |

## Retryability Rules

`retryable = true` is emitted only for known transient conditions:

- `MAIN-TIMEOUT`
- `MAIN-UNAVAILABLE`
- `ENRICH-TIMEOUT`
- `ENRICH-UNAVAILABLE`
- `CLIENT-RATE-LIMITED`
- `PLATFORM-OVERLOADED`

`retryable = false` is emitted for:

- validation and malformed request failures;
- authentication and authorization failures;
- not found;
- bad response, invalid payload, and contract violation;
- orchestration, merge, patch, invariant, mapping, and configuration failures;
- internal bugs and unclassified platform failures.

For optional enrichment failures recorded as `meta.parts.<part>.status = FAILED`, there is no request-level `retryable` field because the request succeeded. Clients that inspect `meta.parts` may treat `ENRICH-TIMEOUT` and `ENRICH-UNAVAILABLE` as transient part-level failures, but that is separate from the HTTP response contract.

## Security and Information Hiding

Public response bodies must never contain:

- stack traces;
- exception class names;
- downstream raw bodies or downstream problem documents;
- downstream URLs, hostnames, IPs, ports, or raw status lines;
- downstream headers;
- internal Spring bean names;
- internal class names or package names;
- configuration property names or environment identifiers;
- SQL, database identifiers, query plans, or storage internals;
- secrets, tokens, cookies, credentials, or authorization header values;
- implementation-specific exception messages.

Allowed public diagnostics are the stable problem fields, logical dependency names, validation pointers/messages, and the optional `part` and `criticality` extensions on part-level fatal failures.

Logs, metrics, and traces may carry richer diagnostic context, but only behind internal access controls and with normal PII/secrets scrubbing.

## Examples

### 1. Invalid client request

```json
{
  "type": "/problems/validation",
  "title": "Request validation failed",
  "status": 400,
  "detail": "One or more request fields failed validation.",
  "instance": "/requests/fa3c2b1d4e5f6a7b8c9d0e1f2a3b4c5d",
  "errorCode": "CLIENT-VALIDATION",
  "category": "CLIENT_REQUEST",
  "traceId": "fa3c2b1d4e5f6a7b8c9d0e1f2a3b4c5d",
  "retryable": false,
  "timestamp": "2026-04-22T10:15:30Z",
  "violations": [
    { "pointer": "/ids", "message": "must not be empty" }
  ]
}
```

### 2. Main dependency timeout

```json
{
  "type": "/problems/main/timeout",
  "title": "Main dependency timed out",
  "status": 504,
  "detail": "The main dependency call did not complete within the allowed time.",
  "instance": "/requests/0af7651916cd43dd8448eb211c80319c",
  "errorCode": "MAIN-TIMEOUT",
  "category": "MAIN_DEPENDENCY",
  "dependency": "main",
  "traceId": "0af7651916cd43dd8448eb211c80319c",
  "retryable": true,
  "timestamp": "2026-04-22T10:15:30Z"
}
```

### 3. REQUIRED enrichment timeout

```json
{
  "type": "/problems/enrichment/timeout",
  "title": "Enrichment dependency timed out",
  "status": 504,
  "detail": "A required enrichment call did not complete within the allowed time.",
  "instance": "/requests/4bf92f3577b34da6a3ce929d0e0e4736",
  "errorCode": "ENRICH-TIMEOUT",
  "category": "ENRICHMENT_DEPENDENCY",
  "dependency": "enricher:owners",
  "part": "owners",
  "criticality": "REQUIRED",
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
  "retryable": true,
  "timestamp": "2026-04-22T10:15:30Z"
}
```

### 4. OPTIONAL enrichment timeout

```json
{
  "customerId": "cust-1",
  "data": [
    { "id": "group-1" }
  ],
  "meta": {
    "parts": {
      "owners": {
        "status": "FAILED",
        "criticality": "OPTIONAL",
        "reason": "TIMEOUT",
        "errorCode": "ENRICH-TIMEOUT"
      }
    }
  }
}
```

### 5. No keys for enrichment

```json
{
  "customerId": "cust-1",
  "meta": {
    "parts": {
      "account": {
        "status": "SKIPPED",
        "criticality": "REQUIRED",
        "reason": "NO_KEYS_IN_MAIN"
      }
    }
  }
}
```

### 6. Enrichment 404 or empty result

```json
{
  "customerId": "cust-1",
  "data": [
    { "accounts": [{ "id": "acc-a" }] }
  ],
  "meta": {
    "parts": {
      "account": {
        "status": "EMPTY",
        "criticality": "REQUIRED",
        "reason": "DOWNSTREAM_NOT_FOUND"
      }
    }
  }
}
```

For an empty downstream body or no matched response data, the same shape is used with:

```json
{
  "status": "EMPTY",
  "criticality": "REQUIRED",
  "reason": "DOWNSTREAM_EMPTY"
}
```

### 7. Merge failure

```json
{
  "type": "/problems/orchestration/merge-failed",
  "title": "Aggregation failed",
  "status": 500,
  "detail": "The service could not assemble the aggregated response.",
  "instance": "/requests/b7ad6b7169203331d2f2e3a6f1c8d9e0",
  "errorCode": "ORCH-MERGE-FAILED",
  "category": "ORCHESTRATION",
  "traceId": "b7ad6b7169203331d2f2e3a6f1c8d9e0",
  "retryable": false,
  "timestamp": "2026-04-22T10:15:30Z"
}
```

## Test Expectations

Repository tests or contract tests should validate:

- every `ProblemCatalog` entry has stable `type`, `title`, `status`, `detail`, `category`, `errorCode`, and `retryable`;
- invalid JSON body maps to `CLIENT-INVALID-BODY`;
- bean validation, invalid path/query values, and unknown include map to `CLIENT-VALIDATION` with `violations`;
- framework 401, 403, 404, 405, 406, 415, and 429 map to catalog entries;
- main timeout, unavailable, auth failure, unexpected status, invalid payload, empty body, and non-object body are fatal and contain no aggregate data;
- raw main 404 maps as `MAIN-BAD-RESPONSE` unless an explicit domain-not-found classifier exists;
- selected enrichment with no root keys returns 200 with `SKIPPED / NO_KEYS_IN_MAIN`;
- unsupported context returns 200 with `SKIPPED / UNSUPPORTED_CONTEXT`;
- empty dependency cascades to dependents as `SKIPPED / DEPENDENCY_EMPTY`;
- enrichment empty body returns 200 with `EMPTY / DOWNSTREAM_EMPTY`;
- enrichment 404 where handled by the step returns 200 with `EMPTY / DOWNSTREAM_NOT_FOUND`;
- required enrichment timeout, auth failure, 5xx/unexpected status, unavailable, invalid payload, and fatal contract violation return `ProblemDetail`;
- optional enrichment timeout, auth failure, 5xx/unexpected status, unavailable, invalid payload, and downstream-client contract violation return 200 with `FAILED`, `criticality: "OPTIONAL"`, `reason`, and `errorCode`;
- optional part orchestration, merge, patch, wrong-result-name, empty publisher, workflow-definition, and invariant failures remain fatal;
- error responses never contain partial aggregate data or `meta.parts`;
- success responses never contain RFC 9457 problem fields;
- public response bodies do not leak stack traces, exception class names, raw downstream bodies, internal hostnames, tokens, bean names, class/package names, or implementation exception messages;
- every response carries a valid `traceparent`, and every problem body contains `traceId`, `timestamp`, and `instance`;
- `RejectedExecutionException` maps to `PLATFORM-OVERLOADED` with `Retry-After`.

## Current Review Notes

No current code/test/docs conflict was found for the implemented model described here. The README, enums, executor, failure policy, and tests agree that:

- `FAILED` exists as a success-side part outcome;
- `REQUIRED` is default criticality and `OPTIONAL` is opt-in;
- optional downstream failures represented as `DownstreamClientException` are recorded as `FAILED`;
- required downstream failures are fatal;
- soft data absence is represented as `EMPTY` or `SKIPPED`;
- orchestration and merge/invariant failures are fatal.

One nuance to keep explicit: not every `ENRICH-CONTRACT-VIOLATION` is optional-safe. Only contract violations represented as `DownstreamClientException` are eligible for optional `FAILED`. Contract violations represented as `EnrichmentDependencyException` are fatal because the failure policy only downgrades `DownstreamClientException` for optional parts.

The main open product decision is whether required public enrichments should keep the current soft data-absence semantics or move to request-level failures for missing keys, empty downstream data, enrichment 404, partial key coverage, and schema mismatches. That decision is tracked in [architecture-review.md](architecture-review.md).
