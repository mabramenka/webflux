# Aggregation Facade — Error Handling Architecture

**Stack:** Spring Boot 4 / Spring Framework 7
**External error contract:** RFC 9457 (Problem Details for HTTP APIs)
**Document type:** Architecture specification (intended as input for code generation)

---

## 1. Formal Problem Statement

This service is a Domain Facade that exposes an aggregated view over several backend systems. For every incoming request it performs one call to a main upstream endpoint, extracts identifiers from the main response, invokes one or more optional enrichment endpoints using those identifiers, and returns a single merged representation to the client. The facade owns its external contract and does not act as a transparent proxy.

Error handling follows a **soft / fatal split**. Data absence is soft — per-part skip, not an error. Infrastructure failure is fatal — the whole request aborts with an RFC 9457 problem document. Specifically:

- **Fatal (aborts the request):** failures on the *main* call (empty/unreadable body, non-success status, transport error, timeout); auth failures (`401`/`403`) on *any* downstream; enricher transport failures, timeouts, `5xx`, or decoding errors; merge exceptions; internal invariant violations.
- **Soft (part is recorded as `EMPTY` or `SKIPPED`, request succeeds):** main payload missing the keys a part needs (`NO_KEYS_IN_MAIN`); `supports(context)` returned `false` (`UNSUPPORTED_CONTEXT`); enricher body empty (`DOWNSTREAM_EMPTY`); enricher returned `404` (`DOWNSTREAM_NOT_FOUND`); a required dependency applied nothing (`DEPENDENCY_EMPTY`).

Success responses carry a `meta.parts` object that names each requested or transitively-required part with its `status` (`APPLIED` / `EMPTY` / `SKIPPED`) and, for non-`APPLIED` parts, a machine-readable `reason`. Error responses conform to RFC 9457 using Spring Framework 7 `ProblemDetail` / `ErrorResponse` constructs and a stable, facade-owned vocabulary of problem types, error codes, and categories. No downstream internals — hostnames, raw bodies, stack traces, bean names — are ever exposed to the client.

---

## 2. Scope and Context

### 2.1 What this document defines

- The error classification model for the facade.
- The decision rules for mapping internal failures to outward-facing responses.
- The RFC 9457 contract: type URIs, titles, details, instances, and extension fields.
- The concrete mapping between failure scenarios and HTTP status / problem type.
- The boundary between internal diagnostic data and client-visible data.

### 2.2 What this document does not define

- Business-level validation rules.
- Concrete Spring bean wiring, retry, circuit breaker, or timeout values.
- Authentication mechanism details.
- Observability backend configuration (logging, tracing exporters).

### 2.3 Spring Framework 7 building blocks assumed

- `org.springframework.http.ProblemDetail` — body representation.
- `org.springframework.web.ErrorResponse` / `ErrorResponseException` — throwable carriers of problem details.
- `@RestControllerAdvice` with `ResponseEntityExceptionHandler` as base — central exception mapping.
- Spring HTTP service clients backed by `WebClient` with a shared error filter / response normalizer — translates downstream failures into facade exceptions before they escape the gateway layer.

---

## 3. Design Principles

1. **Soft / fatal split.** Data absence is a per-part signal, not an error. Infrastructure failure is a request-level failure. A response is either fully successful (with `meta.parts` reporting any EMPTY/SKIPPED parts) or entirely replaced by a `ProblemDetail`. A success response never contains a `ProblemDetail`, and an error response never contains partial data.
2. **Main is strict; enrichers are optional.** A failed or empty main call aborts the whole request. A failed enrichment infrastructure call (auth, `5xx`, timeout, transport, decoding) also aborts the whole request. Enricher *data absence* (empty body, `404`, main payload missing keys, unsupported context) is soft and reported in `meta.parts`.
3. **Stable external contract.** Clients integrate against facade-owned `type` URIs and `errorCode` values for errors, and against `meta.parts.<name>.{status, reason}` for soft per-part outcomes. Downstream changes must not change either contract.
4. **Information hiding by default.** Everything is internal unless explicitly promoted to the external contract.
5. **Named failure modes.** Every failure has a stable identity: a `type` URI, a title, a category, and an `errorCode`. Every soft skip has a stable `PartOutcomeStatus` and `PartSkipReason`. No anonymous 500s; no silently-omitted parts.
6. **Traceability before narrative.** Error responses always carry a `traceId` and an `instance`. Detailed forensics live in logs, not in the body.
7. **Retryability is explicit.** Clients do not infer retry policy from status codes; the `retryable` extension states it.
8. **Downstream opacity.** Clients cannot tell which specific backend failed by reading a hostname, URL, or body fragment. At most they see a logical dependency name from a fixed enum.

---

## 4. Error Classification

The facade recognises five top-level categories. Every thrown or caught error must map to exactly one.

| Category                    | Meaning                                                                                             | Typical HTTP band | Client fault? |
| --------------------------- | --------------------------------------------------------------------------------------------------- | ----------------- | ------------- |
| `CLIENT_REQUEST`            | The request is malformed, unauthenticated, forbidden, or targets a non-existent domain resource.    | 4xx               | Yes           |
| `MAIN_DEPENDENCY`           | The main upstream endpoint is unreachable, slow, returned an invalid or contract-violating payload. | 502 / 504         | No            |
| `ENRICHMENT_DEPENDENCY`     | A required enrichment endpoint failed with any of the same failure modes.                           | 502 / 504         | No            |
| `ORCHESTRATION`             | A failure inside facade logic: merge error, invariant violation, misconfiguration, mapping bug.     | 500               | No            |
| `PLATFORM`                  | Runtime or infrastructure problem: OOM, thread starvation, unhandled exception, bean init failure.  | 500 / 503         | No            |

### 4.1 Sub-classification (for `errorCode` and diagnostics)

Each category has a closed set of sub-types. This set is the authoritative catalog for `errorCode` values.

**CLIENT_REQUEST**
- `CLIENT-VALIDATION` — request shape / field validation failed.
- `CLIENT-UNAUTHENTICATED` — no or invalid credentials.
- `CLIENT-FORBIDDEN` — authenticated but not authorised.
- `CLIENT-NOT-FOUND` — the requested domain resource does not exist.
- `CLIENT-METHOD-NOT-ALLOWED` — HTTP method is not supported for the addressed route.
- `CLIENT-NOT-ACCEPTABLE` — `Accept` negotiation cannot be honoured.
- `CLIENT-UNSUPPORTED-MEDIA` — request content media type is not supported.
- `CLIENT-RATE-LIMITED` — quota exceeded.

**MAIN_DEPENDENCY**
- `MAIN-TIMEOUT` — response not received within budget.
- `MAIN-UNAVAILABLE` — connection refused, DNS failure, `503`, circuit open.
- `MAIN-BAD-RESPONSE` — non-success status outside a whitelisted domain-meaningful set.
- `MAIN-INVALID-PAYLOAD` — response is not valid JSON or fails schema.
- `MAIN-CONTRACT-VIOLATION` — response is valid JSON but is empty or missing keys required for enrichment.
- `MAIN-AUTH-FAILED` — facade cannot authenticate to main (credential / token problem).

**ENRICHMENT_DEPENDENCY** (only for fatal enricher failures — data absence is soft, see §4.3)
- `ENRICH-TIMEOUT`
- `ENRICH-UNAVAILABLE`
- `ENRICH-BAD-RESPONSE` — non-success, non-`404` status outside the auth band.
- `ENRICH-INVALID-PAYLOAD`
- `ENRICH-CONTRACT-VIOLATION` — reserved for nested batch calls that must succeed in full (e.g. the beneficial-owners tree resolver); a top-level enricher `404` is *not* a contract violation, it is a soft `DOWNSTREAM_NOT_FOUND`.
- `ENRICH-AUTH-FAILED`

**ORCHESTRATION**
- `ORCH-MERGE-FAILED` — aggregation step threw.
- `ORCH-INVARIANT-VIOLATED` — internal invariant broken (e.g. enricher returned an object for the wrong key).
- `ORCH-MAPPING-FAILED` — response DTO assembly failed.
- `ORCH-CONFIG-INVALID` — required runtime configuration missing or malformed after the application has started. Configuration errors detected during bean creation may fail startup before an HTTP response exists.

**PLATFORM**
- `PLATFORM-INTERNAL` — unclassified unchecked exception, last-resort bucket.
- `PLATFORM-OVERLOADED` — thread pool exhausted, backpressure, shedding.

### 4.3 Soft outcomes (per-part, reported in `meta.parts`, not errors)

Soft outcomes never produce an HTTP error response. They are recorded on `AggregationPartResult.NoOp` and surfaced in the success body under `meta.parts.<partName>`.

| `PartOutcomeStatus` | `PartSkipReason`          | Trigger                                                                                       |
| ------------------- | ------------------------- | --------------------------------------------------------------------------------------------- |
| `APPLIED`           | *(none)*                  | Part produced a replacement or merge patch that was applied to the root.                      |
| `EMPTY`             | `DOWNSTREAM_EMPTY`        | Enricher returned an empty body (no JSON).                                                     |
| `EMPTY`             | `DOWNSTREAM_NOT_FOUND`    | Enricher returned `404`. The facade treats this as "no data for these keys," not an error.   |
| `SKIPPED`           | `NO_KEYS_IN_MAIN`         | Main payload did not yield any keys for the part to fetch (e.g. missing `accounts[*].id`).   |
| `SKIPPED`           | `UNSUPPORTED_CONTEXT`     | Part's `supports(context)` returned `false`.                                                  |
| `SKIPPED`           | `DEPENDENCY_EMPTY`        | A dependency the part declared did not apply (returned `NoOp` or was itself skipped).         |

### 4.4 Exception hierarchy (conceptual)

The implementation should define a single sealed hierarchy rooted at a facade-specific base that extends `ErrorResponseException`. Suggested shape:

- `FacadeException` *(base; extends `ErrorResponseException`)*
  - `ClientRequestException`
  - `MainDependencyException`
  - `EnrichmentDependencyException`
  - `OrchestrationException`
  - `PlatformException`

Each concrete sub-type corresponds to one `errorCode` in the catalog above. This keeps the mapping from exception → `ProblemDetail` purely mechanical and testable.

---

## 5. Decision Rules

For every failure the following questions are answered in order.

### 5.1 Does it break the whole request?

**Fatal → yes.** Any `FacadeException` (the five categories in §4) aborts the request with a `ProblemDetail`. No partial data is emitted alongside the error.

**Soft → no.** A part that returns `AggregationPartResult.NoOp` (EMPTY or SKIPPED) is recorded in `meta.parts` and the request proceeds. Dependents of a skipped/empty part are themselves marked `SKIPPED / DEPENDENCY_EMPTY`. The response body is a normal success body — no `ProblemDetail`.

The soft/fatal decision is made inside the part's `execute(rootSnapshot, context)` via the sealed `AggregationPartResult` hierarchy: `ReplaceDocument` or `MergePatch` → applied; `NoOp` → recorded; thrown `FacadeException` → fatal. Any other exception thrown by a part is considered an internal invariant violation and mapped to `ORCH-INVARIANT-VIOLATED`.

### 5.2 What HTTP status does it produce?

| Category                | Status rule                                                                                                          |
| ----------------------- | -------------------------------------------------------------------------------------------------------------------- |
| `CLIENT_REQUEST`        | The specific 4xx (400, 401, 403, 404, 405, 406, 415, 429).                                                           |
| `MAIN_DEPENDENCY`       | `504` for timeout / unavailability; `502` for bad status, invalid payload, or contract violation.                    |
| `ENRICHMENT_DEPENDENCY` | Same rule as main: `504` for timeout / unavailability; `502` for bad status, invalid payload, contract violation.    |
| `ORCHESTRATION`         | `500` — always. The cause is internal logic.                                                                         |
| `PLATFORM`              | `500` for uncaught; `503` only for explicit load shedding with `Retry-After`.                                        |

### 5.3 Which `problem type` is selected?

The `type` URI is derived deterministically from the sub-code (see §4.1 and §8 for the full catalog). `type` is relative, slugified, and grouped by category, for example `/problems/main/timeout` or `/problems/enrichment/contract-violation`.

### 5.4 When is a failure classified as `MAIN_DEPENDENCY` vs `ORCHESTRATION`?

This is the ambiguous boundary and must be decided by the following rule set:

| Situation                                                                                               | Classification      |
| ------------------------------------------------------------------------------------------------------- | ------------------- |
| The HTTP call to main did not complete successfully (timeout, connection refused, 5xx, DNS).            | `MAIN_DEPENDENCY`   |
| Main returned an empty body or a non-object payload.                                                     | `MAIN_DEPENDENCY` (contract violation) |
| Main returned a payload that our client code fails to deserialise.                                       | `MAIN_DEPENDENCY` (invalid payload)     |
| Main returned a payload that deserialises, but our mapping code threw while transforming it.            | `ORCHESTRATION` (mapping failed)        |
| Main failure is explicitly classified as a domain not-found outcome — the requested resource genuinely does not exist. | `CLIENT_REQUEST` (not found) |
| Main returned a raw downstream `404` without domain-not-found classification.                           | `MAIN_DEPENDENCY` (bad response)        |
| Facade failed to pick a required runtime configuration value after startup.                              | `ORCHESTRATION` (config invalid)        |
| Merge of main + enrichments threw or produced an invalid DTO.                                           | `ORCHESTRATION` (merge failed)          |

Main payload containing no keys for a given enrichment is *not* a fault; that part is soft-skipped with `NO_KEYS_IN_MAIN` (see §4.3).

**Heuristic:** if the failure can be reproduced by pointing the facade at a known-good fake main that returns a known-good payload, it is `ORCHESTRATION`. Otherwise it is `MAIN_DEPENDENCY`.

### 5.5 Retryability rule

`retryable = true` is emitted only for:
- `MAIN-TIMEOUT`, `MAIN-UNAVAILABLE`
- `ENRICH-TIMEOUT`, `ENRICH-UNAVAILABLE`
- `CLIENT-RATE-LIMITED`
- `PLATFORM-OVERLOADED` (with `Retry-After`)

All other codes are `retryable = false`. Contract violations, auth failures, validation errors, and orchestration bugs never advertise as retryable.

---

## 6. RFC 9457 Error Contract

### 6.1 Fields

| Field       | Source                                                                                                | Notes                                                                                               |
| ----------- | ----------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------- |
| `type`      | Relative URI from the catalog, e.g. `/problems/enrichment/timeout`.                                    | Stable. Part of public contract. Relative so the facade is deployable behind any base path.         |
| `title`     | Fixed, human-readable, constant per `type`. E.g. `"Enrichment dependency timed out"`.                  | No dynamic content. No identifiers, no hostnames.                                                   |
| `status`    | HTTP status from §5.2.                                                                                 | Matches response line.                                                                              |
| `detail`    | Short, generic elaboration per `type`. May include high-level cause class but never raw downstream.   | No stack traces, no raw JSON, no PII, no internal identifiers.                                      |
| `instance`  | Relative URI of the failing request instance, e.g. `/requests/{traceId}`.                              | Always present. Used for support lookup.                                                            |

### 6.2 Extension fields (always present unless noted)

| Extension    | Type     | Meaning                                                                                                             |
| ------------ | -------- | ------------------------------------------------------------------------------------------------------------------- |
| `errorCode`  | string   | Stable machine-readable code from §4.1. Primary integration key for clients.                                        |
| `category`   | string   | One of `CLIENT_REQUEST`, `MAIN_DEPENDENCY`, `ENRICHMENT_DEPENDENCY`, `ORCHESTRATION`, `PLATFORM`.                    |
| `traceId`    | string   | Distributed trace id (W3C traceparent) for correlation.                                                              |
| `retryable`  | boolean  | See §5.5.                                                                                                           |
| `timestamp`  | string   | RFC 3339 UTC timestamp at the moment the facade emits the response.                                                  |
| `dependency` | string   | *Only for MAIN / ENRICHMENT categories.* Logical name from a fixed enum (e.g. `"main"`, `"enricher:profile"`). Never a URL, host, or IP. |
| `violations` | array    | *Only for `CLIENT-VALIDATION`.* Each entry has `pointer` (JSON Pointer to the offending field) and `message` (generic). |

### 6.3 Media type and headers

- Content-Type: `application/problem+json` on all error responses.
- `Retry-After` is included when and only when `retryable = true` and a concrete wait is recommended.
- A valid `traceparent` header is present on every response (success or error) for correlation. Valid inbound values are echoed; otherwise the facade generates a new W3C trace context.

### 6.4 Invariants

1. `type`, `title`, `errorCode`, `category` are 1-to-1: a given `errorCode` always produces the same three others.
2. `type` is never built dynamically. It comes from the catalog.
3. `detail` is a template string with no runtime-interpolated data except sanitized logical dependency names and validation pointers.
4. `instance` is always unique per request. It is safe to share.
5. No response ever carries both partial data and a `ProblemDetail`. Error response bodies contain exclusively the problem details.

### 6.5 Success-side `meta.parts` contract

Success responses (`2xx`) carry a `meta.parts` object alongside the aggregated data. One entry per effectively-selected part — explicit selections from `request.include()` plus parts pulled in as dependencies.

| Field      | Type    | Values                                                                                   |
| ---------- | ------- | ---------------------------------------------------------------------------------------- |
| `status`   | string  | `APPLIED`, `EMPTY`, `SKIPPED` (from the `PartOutcomeStatus` enum).                       |
| `reason`   | string  | Present only when `status != APPLIED`. One of the `PartSkipReason` values from §4.3.     |

`meta.parts` entry order is stable: insertion order of the effective selection (explicit selections first, dependencies expanded in graph order). Clients may rely on the enum values but must not parse ordering.

---

## 7. Mapping Strategy

### 7.1 Downstream failure mapping

Applies uniformly to the main endpoint and to every enricher. The table differentiates by category in the sub-code prefix (`MAIN-*` vs `ENRICH-*`).

| Failure scenario                                              | HTTP | Category                  | `type` (relative)                              | `errorCode`                  | Retryable |
| ------------------------------------------------------------- | ---- | ------------------------- | ---------------------------------------------- | ---------------------------- | --------- |
| Timeout calling main                                          | 504  | `MAIN_DEPENDENCY`         | `/problems/main/timeout`                       | `MAIN-TIMEOUT`               | true      |
| Timeout calling enricher                                      | 504  | `ENRICHMENT_DEPENDENCY`   | `/problems/enrichment/timeout`                 | `ENRICH-TIMEOUT`             | true      |
| Main unreachable (conn refused / DNS / 503 / circuit open)    | 504  | `MAIN_DEPENDENCY`         | `/problems/main/unavailable`                   | `MAIN-UNAVAILABLE`           | true      |
| Enricher unreachable                                          | 504  | `ENRICHMENT_DEPENDENCY`   | `/problems/enrichment/unavailable`             | `ENRICH-UNAVAILABLE`         | true      |
| Main returns non-success, non-domain status (5xx, unexpected) | 502  | `MAIN_DEPENDENCY`         | `/problems/main/bad-response`                  | `MAIN-BAD-RESPONSE`          | false     |
| Enricher returns non-success, non-domain status               | 502  | `ENRICHMENT_DEPENDENCY`   | `/problems/enrichment/bad-response`            | `ENRICH-BAD-RESPONSE`        | false     |
| Main returns invalid JSON / schema failure                    | 502  | `MAIN_DEPENDENCY`         | `/problems/main/invalid-payload`               | `MAIN-INVALID-PAYLOAD`       | false     |
| Enricher returns invalid JSON / schema failure                | 502  | `ENRICHMENT_DEPENDENCY`   | `/problems/enrichment/invalid-payload`         | `ENRICH-INVALID-PAYLOAD`     | false     |
| Main returns empty body or a non-object payload               | 502  | `MAIN_DEPENDENCY`         | `/problems/main/contract-violation`            | `MAIN-CONTRACT-VIOLATION`    | false     |
| Enricher returns `404`                                         | *(soft)* | *n/a — `meta.parts.<name> = { status: EMPTY, reason: DOWNSTREAM_NOT_FOUND }`* | *n/a* | *n/a* | *n/a* |
| Nested batch call inside an enricher (e.g. beneficial-owners tree) returns `404` or partial data | 502 | `ENRICHMENT_DEPENDENCY` | `/problems/enrichment/contract-violation` | `ENRICH-CONTRACT-VIOLATION` | false |
| Main returns `401` / `403` to facade                          | 502  | `MAIN_DEPENDENCY`         | `/problems/main/auth-failed`                   | `MAIN-AUTH-FAILED`           | false     |
| Enricher returns `401` / `403` to facade                      | 502  | `ENRICHMENT_DEPENDENCY`   | `/problems/enrichment/auth-failed`             | `ENRICH-AUTH-FAILED`         | false     |
| Main returns a raw `404` without domain-not-found classification | 502 | `MAIN_DEPENDENCY`        | `/problems/main/bad-response`                  | `MAIN-BAD-RESPONSE`          | false     |
| Main failure is explicitly classified as domain not found       | 404  | `CLIENT_REQUEST`          | `/problems/not-found`                          | `CLIENT-NOT-FOUND`           | false     |

### 7.2 Internal failure mapping

| Failure scenario                                              | HTTP | Category           | `type` (relative)                        | `errorCode`                 | Retryable |
| ------------------------------------------------------------- | ---- | ------------------ | ---------------------------------------- | --------------------------- | --------- |
| Merge / aggregation code throws                               | 500  | `ORCHESTRATION`    | `/problems/orchestration/merge-failed`   | `ORCH-MERGE-FAILED`         | false     |
| DTO mapping throws                                            | 500  | `ORCHESTRATION`    | `/problems/orchestration/mapping-failed` | `ORCH-MAPPING-FAILED`       | false     |
| Internal invariant violation                                  | 500  | `ORCHESTRATION`    | `/problems/orchestration/invariant`      | `ORCH-INVARIANT-VIOLATED`   | false     |
| Missing / malformed runtime configuration detected after startup | 500 | `ORCHESTRATION`    | `/problems/orchestration/config`         | `ORCH-CONFIG-INVALID`       | false     |
| Unclassified unchecked exception                              | 500  | `PLATFORM`         | `/problems/platform/internal`            | `PLATFORM-INTERNAL`         | false     |
| Load shedding / thread pool rejection                         | 503  | `PLATFORM`         | `/problems/platform/overloaded`          | `PLATFORM-OVERLOADED`       | true      |

### 7.3 Client request mapping

| Failure scenario                                   | HTTP | Category         | `type` (relative)               | `errorCode`                | Retryable |
| -------------------------------------------------- | ---- | ---------------- | ------------------------------- | -------------------------- | --------- |
| Bean validation / malformed body                   | 400  | `CLIENT_REQUEST` | `/problems/validation`          | `CLIENT-VALIDATION`        | false     |
| Missing / invalid credentials                      | 401  | `CLIENT_REQUEST` | `/problems/unauthenticated`     | `CLIENT-UNAUTHENTICATED`   | false     |
| Authenticated but not authorised                   | 403  | `CLIENT_REQUEST` | `/problems/forbidden`           | `CLIENT-FORBIDDEN`         | false     |
| Domain resource not found                          | 404  | `CLIENT_REQUEST` | `/problems/not-found`           | `CLIENT-NOT-FOUND`         | false     |
| HTTP method not supported for route                 | 405  | `CLIENT_REQUEST` | `/problems/method-not-allowed` | `CLIENT-METHOD-NOT-ALLOWED` | false     |
| Accept not honourable                               | 406  | `CLIENT_REQUEST` | `/problems/not-acceptable`     | `CLIENT-NOT-ACCEPTABLE`    | false     |
| Unsupported media type                              | 415  | `CLIENT_REQUEST` | `/problems/unsupported-media`  | `CLIENT-UNSUPPORTED-MEDIA` | false     |
| Quota / rate limit exceeded                        | 429  | `CLIENT_REQUEST` | `/problems/rate-limited`        | `CLIENT-RATE-LIMITED`      | true      |

### 7.4 Mapping flow

1. Controller receives request.
2. Bean validation runs. Failures → `CLIENT-VALIDATION`. Unknown names in `request.include()` are validated up front and rejected with `CLIENT-VALIDATION`.
3. Service calls main via a Spring HTTP service client backed by `WebClient`. The shared downstream response normalizer classifies HTTP-layer, transport, empty-body, and decoding outcomes into the `MAIN-*` sub-codes. Empty body here is always fatal (`MAIN-CONTRACT-VIOLATION`).
4. Facade validates main payload against the contract (object-shaped, non-array). Failures → `MAIN-CONTRACT-VIOLATION`.
5. Facade expands dependencies and fans out to each selected part per dependency level. For each part:
   - If `supports(context)` returns `false` → `NoOp(SKIPPED, UNSUPPORTED_CONTEXT)`.
   - If the part cannot derive keys from the main payload → `NoOp(SKIPPED, NO_KEYS_IN_MAIN)`.
   - Enricher calls use `DownstreamClientResponses.optionalBody` so an empty body flows into `NoOp(EMPTY, DOWNSTREAM_EMPTY)`, and a `404` is caught by `AggregationEnrichment.execute` and turned into `NoOp(EMPTY, DOWNSTREAM_NOT_FOUND)`.
   - Any other enricher failure (`401`/`403`, `5xx`, timeout, transport, decoding) remains fatal and uses the `ENRICH-*` sub-codes.
   - A dependent part whose dependency did not apply → `NoOp(SKIPPED, DEPENDENCY_EMPTY)` without calling the downstream.
6. Applied part results are written to the root in stable graph order before the next dependency level starts.
7. Merge step inside a part throws → wrapped as `ORCH-MERGE-FAILED`. A part returning a result for the wrong name, or an empty `Mono` → `ORCH-INVARIANT-VIOLATED`.
8. On success, `AggregateService` attaches `meta.parts.<name> = { status, reason? }` for every effectively-selected part.
9. Any uncaught exception at controller boundary → `PLATFORM-INTERNAL`.
10. The global `@RestControllerAdvice` (`AggregationErrorResponseAdvice`) translates the `FacadeException` hierarchy into `ProblemDetail` plus the extensions from §6.2.

---

## 8. Problem Type Catalog

A single authoritative list. Every row is the canonical definition. Code generation should materialise this as an enum or equivalent.

| `errorCode`                 | `type`                                          | `title`                                       | HTTP | Category                 | Retryable |
| --------------------------- | ----------------------------------------------- | --------------------------------------------- | ---- | ------------------------ | --------- |
| `CLIENT-VALIDATION`         | `/problems/validation`                          | Request validation failed                     | 400  | `CLIENT_REQUEST`         | false     |
| `CLIENT-UNAUTHENTICATED`    | `/problems/unauthenticated`                     | Authentication required                       | 401  | `CLIENT_REQUEST`         | false     |
| `CLIENT-FORBIDDEN`          | `/problems/forbidden`                           | Access denied                                  | 403  | `CLIENT_REQUEST`         | false     |
| `CLIENT-NOT-FOUND`          | `/problems/not-found`                           | Resource not found                             | 404  | `CLIENT_REQUEST`         | false     |
| `CLIENT-METHOD-NOT-ALLOWED` | `/problems/method-not-allowed`                  | Method not allowed                             | 405  | `CLIENT_REQUEST`         | false     |
| `CLIENT-NOT-ACCEPTABLE`     | `/problems/not-acceptable`                      | Not acceptable                                 | 406  | `CLIENT_REQUEST`         | false     |
| `CLIENT-UNSUPPORTED-MEDIA`  | `/problems/unsupported-media`                   | Unsupported media type                         | 415  | `CLIENT_REQUEST`         | false     |
| `CLIENT-RATE-LIMITED`       | `/problems/rate-limited`                        | Rate limit exceeded                            | 429  | `CLIENT_REQUEST`         | true      |
| `MAIN-TIMEOUT`              | `/problems/main/timeout`                        | Main dependency timed out                      | 504  | `MAIN_DEPENDENCY`        | true      |
| `MAIN-UNAVAILABLE`          | `/problems/main/unavailable`                    | Main dependency unavailable                    | 504  | `MAIN_DEPENDENCY`        | true      |
| `MAIN-BAD-RESPONSE`         | `/problems/main/bad-response`                   | Main dependency returned an unexpected status  | 502  | `MAIN_DEPENDENCY`        | false     |
| `MAIN-INVALID-PAYLOAD`      | `/problems/main/invalid-payload`                | Main dependency returned an invalid payload    | 502  | `MAIN_DEPENDENCY`        | false     |
| `MAIN-CONTRACT-VIOLATION`   | `/problems/main/contract-violation`             | Main dependency payload violates contract      | 502  | `MAIN_DEPENDENCY`        | false     |
| `MAIN-AUTH-FAILED`          | `/problems/main/auth-failed`                    | Main dependency refused authentication         | 502  | `MAIN_DEPENDENCY`        | false     |
| `ENRICH-TIMEOUT`            | `/problems/enrichment/timeout`                  | Enrichment dependency timed out                | 504  | `ENRICHMENT_DEPENDENCY`  | true      |
| `ENRICH-UNAVAILABLE`        | `/problems/enrichment/unavailable`              | Enrichment dependency unavailable              | 504  | `ENRICHMENT_DEPENDENCY`  | true      |
| `ENRICH-BAD-RESPONSE`       | `/problems/enrichment/bad-response`             | Enrichment dependency returned an unexpected status | 502 | `ENRICHMENT_DEPENDENCY` | false |
| `ENRICH-INVALID-PAYLOAD`    | `/problems/enrichment/invalid-payload`          | Enrichment dependency returned an invalid payload   | 502 | `ENRICHMENT_DEPENDENCY` | false |
| `ENRICH-CONTRACT-VIOLATION` | `/problems/enrichment/contract-violation`       | Enrichment dependency payload violates contract     | 502 | `ENRICHMENT_DEPENDENCY` | false |
| `ENRICH-AUTH-FAILED`        | `/problems/enrichment/auth-failed`              | Enrichment dependency refused authentication        | 502 | `ENRICHMENT_DEPENDENCY` | false |
| `ORCH-MERGE-FAILED`         | `/problems/orchestration/merge-failed`          | Aggregation failed                              | 500 | `ORCHESTRATION`          | false     |
| `ORCH-MAPPING-FAILED`       | `/problems/orchestration/mapping-failed`        | Response mapping failed                         | 500 | `ORCHESTRATION`          | false     |
| `ORCH-INVARIANT-VIOLATED`   | `/problems/orchestration/invariant`             | Internal invariant violated                     | 500 | `ORCHESTRATION`          | false     |
| `ORCH-CONFIG-INVALID`       | `/problems/orchestration/config`                | Internal configuration error                    | 500 | `ORCHESTRATION`          | false     |
| `PLATFORM-INTERNAL`         | `/problems/platform/internal`                   | Internal server error                           | 500 | `PLATFORM`               | false     |
| `PLATFORM-OVERLOADED`       | `/problems/platform/overloaded`                 | Service overloaded                              | 503 | `PLATFORM`               | true      |

---

## 9. External Contract Boundaries

### 9.1 What MAY appear in the response body

- `type`, `title`, `status`, `detail`, `instance` (RFC 9457 core).
- `errorCode`, `category`, `traceId`, `timestamp`, `retryable` (always).
- `dependency` — only the logical name from a fixed enum. Allowed values are maintained alongside the catalog.
- `violations` — only for `CLIENT-VALIDATION`, only with JSON Pointer + generic message.

### 9.2 What MUST NEVER appear in the response body

- Stack traces or exception class names.
- Hostnames, IP addresses, ports, URLs of downstream systems.
- Raw downstream response bodies, headers, or status lines.
- Database identifiers, SQL, query plans.
- Authentication material: tokens, cookies, header values.
- Internal Spring bean names, configuration property names.
- PII beyond what the client already provided.
- Environment names, region identifiers, pod names, container ids.

### 9.3 Normalisation rules for downstream errors

1. **Never forward `application/problem+json` bodies from downstream.** If a downstream returns a problem document, it is parsed for diagnostics (logged) and then discarded. The outward-facing problem document is constructed from scratch using the facade's own catalog.
2. **Never forward downstream status codes directly.** Downstream `5xx` becomes `502` or `504` per §7. Downstream `4xx` to facade is classified as `*-AUTH-FAILED`, `*-BAD-RESPONSE`, or `*-CONTRACT-VIOLATION` and is never passed through. A `CLIENT-NOT-FOUND` response is allowed only when facade code explicitly classifies the condition as a domain not-found outcome; a raw downstream `404` is not sufficient.
3. **Never forward downstream headers.** `Retry-After` from downstream is not proxied; it may influence internal retry logic but does not reach the client.
4. **Logical dependency names only.** The `dependency` extension uses names from a maintained enum (e.g. `main`, `enricher:profile`, `enricher:pricing`). Hostnames are never exposed.
5. **Templated details only.** `detail` strings are chosen from a finite table keyed by `errorCode`. No concatenation with runtime strings except the whitelisted `dependency` value.

### 9.4 Observability split

| Destination       | Contents                                                                                                                       |
| ----------------- | ------------------------------------------------------------------------------------------------------------------------------ |
| Response body     | Sanitised problem document (§6, §9.1).                                                                                         |
| Structured logs   | Full diagnostic context: `errorCode`, `category`, `traceId`, downstream host, downstream status, downstream body snippet (length-capped, PII-scrubbed), stack trace, request id, user id if available. |
| Metrics           | Counter per `errorCode`, per `dependency`, per HTTP status.                                                                    |
| Traces            | Span attributes mirror the log fields. The failing span is marked with `error=true` and carries `errorCode`.                   |

This separation is the single enforcement point for §9.2.

---

## 10. Example Responses

### 10.1 Enrichment timeout

```json
{
  "type": "/problems/enrichment/timeout",
  "title": "Enrichment dependency timed out",
  "status": 504,
  "detail": "A required enrichment call did not complete within the allowed time.",
  "instance": "/requests/7c2f4a1e-9a13-4b0a-9d6b-0c8c4b5a3f91",
  "errorCode": "ENRICH-TIMEOUT",
  "category": "ENRICHMENT_DEPENDENCY",
  "dependency": "enricher:profile",
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
  "retryable": true,
  "timestamp": "2026-04-22T10:15:30Z"
}
```

### 10.2 Main contract violation (missing required key)

```json
{
  "type": "/problems/main/contract-violation",
  "title": "Main dependency payload violates contract",
  "status": 502,
  "detail": "The main upstream payload is missing keys required for required enrichment.",
  "instance": "/requests/1d5f6cfc-2c0e-4e2f-9a4d-2f1f4e0c8c4b",
  "errorCode": "MAIN-CONTRACT-VIOLATION",
  "category": "MAIN_DEPENDENCY",
  "dependency": "main",
  "traceId": "0af7651916cd43dd8448eb211c80319c",
  "retryable": false,
  "timestamp": "2026-04-22T10:15:30Z"
}
```

### 10.3 Client validation error

```json
{
  "type": "/problems/validation",
  "title": "Request validation failed",
  "status": 400,
  "detail": "One or more request fields failed validation.",
  "instance": "/requests/2a7bde33-45d5-4e6f-bf1b-5c9a8d1e1f2a",
  "errorCode": "CLIENT-VALIDATION",
  "category": "CLIENT_REQUEST",
  "traceId": "fa3c2b1d4e5f6a7b8c9d0e1f2a3b4c5d",
  "retryable": false,
  "timestamp": "2026-04-22T10:15:30Z",
  "violations": [
    { "pointer": "/customerId", "message": "must not be blank" },
    { "pointer": "/currency",   "message": "must be a valid ISO 4217 code" }
  ]
}
```

### 10.4 Orchestration merge failure

```json
{
  "type": "/problems/orchestration/merge-failed",
  "title": "Aggregation failed",
  "status": 500,
  "detail": "The service could not assemble the aggregated response.",
  "instance": "/requests/6f3b8a1c-7d2e-4a5b-9c0d-1e2f3a4b5c6d",
  "errorCode": "ORCH-MERGE-FAILED",
  "category": "ORCHESTRATION",
  "traceId": "b7ad6b7169203331d2f2e3a6f1c8d9e0",
  "retryable": false,
  "timestamp": "2026-04-22T10:15:30Z"
}
```

---

## 11. Implementation Checklist for Spring Framework 7

This section is prescriptive for code generation. It states *what* must exist, not *how* to write it.

### 11.1 Required components

1. **Catalog enum** — one entry per row in §8. Exposes `type` (URI), `title`, `status`, `category`, `retryable`, `defaultDetail`.
2. **Exception hierarchy** — sealed hierarchy rooted at a `FacadeException` extending `ErrorResponseException`; one concrete class per category (see §4.2). Each concrete exception carries a catalog entry and optional `dependency`.
3. **Gateway layer** — Spring HTTP service clients backed by `WebClient` for main and enrichers with:
   - A shared error filter and response normalizer translating HTTP, transport, empty-body, and decode outcomes into `MAIN-*` / `ENRICH-*` exceptions.
   - Explicit connect / read timeouts per dependency.
   - Classification of `IOException` / timeout types to `*-TIMEOUT` vs `*-UNAVAILABLE`.
4. **Main payload validator** — verifies non-empty response and presence of all keys required for required enrichers. Emits `MAIN-CONTRACT-VIOLATION` on failure.
5. **Orchestrator** — fan-out to required enrichers; first failure cancels the rest; wraps merge/mapping errors into `ORCH-*` exceptions.
6. **Global exception handler** — `@RestControllerAdvice` extending `ResponseEntityExceptionHandler`:
   - Handles the `FacadeException` hierarchy.
   - Handles Spring's built-in exceptions (`MethodArgumentNotValidException`, `HttpMessageNotReadableException`, `HttpMediaTypeNotSupportedException`, etc.) by mapping them to the corresponding `CLIENT-*` catalog entry. If a security filter chain is enabled, its authentication entry point and access-denied handler must delegate to the same problem assembler.
   - Last-resort `Throwable` handler maps to `PLATFORM-INTERNAL`.
7. **ProblemDetail assembler** — single component that, given a catalog entry + context, produces a `ProblemDetail` with all extension fields from §6.2. Single source of truth for serialisation.
8. **Sanitisation filter** — assertion / test-time guard that fails the build if any response body leaks fields outside the whitelist in §9.1.

### 11.2 Tests required

- One contract test per row in §8 (asserts `type`, `title`, `status`, `category`, `errorCode`, `retryable`).
- Negative tests per downstream failure scenario (timeout, unreachable, 5xx, invalid JSON, empty main body, 401, raw main 404, and any explicit domain-not-found classifier).
- Affirmative soft-outcome tests for each row of §4.3: `NO_KEYS_IN_MAIN`, `UNSUPPORTED_CONTEXT`, `DOWNSTREAM_EMPTY`, `DOWNSTREAM_NOT_FOUND`, `DEPENDENCY_EMPTY`. Each asserts the request succeeds and that `meta.parts.<name>` carries the correct `status` / `reason`.
- Orchestration test: a fatal enrichment failure aborts the whole request; invariant-violation tests for empty `Mono` and wrong-name results.
- Leak test: no response body contains strings from a configured blocklist (hostnames, `"Exception"`, `"at ..."` stack trace markers, raw downstream body fragments).

### 11.3 Non-goals for this document

- Retry / circuit breaker policies.
- Timeout values.
- Specific authentication scheme.
- Observability backend.

These are separate decisions layered on top of the contract defined here.

---

## 12. Glossary

- **Facade** — the service specified by this document.
- **Main dependency** — the single upstream endpoint called first per request.
- **Enricher** — a required downstream endpoint called using keys extracted from the main response.
- **Required enrichment** — enrichment that the facade considers mandatory; its failure invalidates the whole response.
- **Contract violation** — a downstream returned a syntactically valid response that does not satisfy the facade's semantic expectations (missing keys, empty data for a valid id, etc.).
- **Orchestration** — facade-internal logic that coordinates main call, enrichment calls, and merge.
- **Catalog** — the authoritative table in §8.
