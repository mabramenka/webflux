# API Error Handling Architecture for an Aggregation Facade

## Spring Boot 4 / Spring Framework 7 / RFC 9457

---

## 1. Goal

This document defines the error-handling model for an API facade that:

1. calls a **main REST endpoint**,
2. receives a JSON payload,
3. calls additional **downstream REST APIs** based on the main payload,
4. enriches the main response with optional data.

The design is based on **RFC 9457 Problem Details for HTTP APIs** and follows a **Domain Facade** approach.

---

## 2. Core Design Decisions

### 2.1 Domain Facade
The API exposes its **own stable error contract**.
Downstream error contracts are normalized and are **not exposed directly** to API clients.

### 2.2 Main Flow Is Mandatory
The main downstream call is required to build the response.
If the main flow fails, the entire request fails.

### 2.3 Enrichment Is Optional
Enrichment failures do **not** fail the whole request.
If enrichment cannot be loaded, the related field is omitted.

### 2.4 RFC 9457 Is Used Only for Terminal Failures
`application/problem+json` is returned only when the request ends in an actual failure.
A successful but incomplete response is represented as a normal business payload with explicit metadata.

---

## 3. Response Model

## 3.1 Error Response
Used only for terminal request failures.

Content type:

```http
application/problem+json
```

Base fields:

- `type`
- `title`
- `status`
- `detail`
- `instance`

Recommended extensions:

- `errorCode`
- `traceId`
- `category`
- `retryable`
- `dependency` (when relevant)
- `violations` (for validation errors)

## 3.2 Successful Response
Used when the main flow succeeds.
Optional enrichment failures are represented in response metadata.

Example:

```json
{
  "data": {
    "id": "123",
    "name": "Alice"
  },
  "meta": {
    "responseStatus": "PARTIAL",
    "traceId": "8f5c2c0d",
    "missingFields": [
      "/customer/addresses",
      "/customer/preferences"
    ],
    "warnings": [
      {
        "code": "ADDRESS_ENRICHMENT_TIMEOUT",
        "message": "Addresses are temporarily unavailable"
      },
      {
        "code": "PREFERENCES_ENRICHMENT_INVALID_RESPONSE",
        "message": "Preferences could not be loaded"
      }
    ],
    "enrichmentStatuses": [
      {
        "name": "addresses",
        "status": "TIMEOUT",
        "retryable": true
      },
      {
        "name": "preferences",
        "status": "INVALID_RESPONSE",
        "retryable": true
      }
    ]
  }
}
```

---

## 4. Error Categories

| Category | Source | Breaks Request | External Representation |
|---|---|---:|---|
| CLIENT | Incoming client request | Yes | RFC 9457 |
| MAIN_DEPENDENCY | Main downstream API | Yes | RFC 9457 |
| ORCHESTRATION | Internal merge/composition logic | Yes | RFC 9457 |
| PLATFORM | Timeout, circuit breaker, resource exhaustion affecting the core flow | Yes | RFC 9457 |
| ENRICHMENT_OPTIONAL | Optional downstream enrichment API | No | Success payload with metadata |

---

## 5. Mapping Matrix

## 5.1 Client Errors

| Scenario | HTTP Status | Problem Type | Retryable | Notes |
|---|---:|---|---:|---|
| Malformed JSON | 400 | `/problems/request-invalid` | No | Request body cannot be parsed |
| Validation failed | 400 or 422 | `/problems/request-validation-failed` | No | Use one consistent policy |
| Unsupported media type | 415 | `/problems/unsupported-media-type` | No | Standard client error |
| Representation not acceptable | 406 | `/problems/not-acceptable` | No | Only if content negotiation is used |
| Unauthorized | 401 | `/problems/authentication-required` | Sometimes | Depends on token refresh/re-authentication |
| Forbidden | 403 | `/problems/access-denied` | No | Caller is authenticated but not allowed |
| Rate limit exceeded | 429 | `/problems/rate-limit-exceeded` | Yes | May include retry guidance |

## 5.2 Main Dependency Errors

| Scenario | HTTP Status | Problem Type | Retryable | Notes |
|---|---:|---|---:|---|
| Connect failure / refused / circuit open | 503 | `/problems/main-dependency-unavailable` | Yes | Dependency is temporarily unavailable |
| Timeout | 504 | `/problems/main-dependency-timeout` | Yes | Dependency did not respond in time |
| Invalid JSON / wrong content type / unreadable body | 502 | `/problems/main-dependency-invalid-response` | Sometimes | Upstream returned an invalid response |
| Upstream 404, meaningful for facade | 404 | `/problems/main-resource-not-found` | No | Main resource genuinely does not exist |
| Upstream 409 | 409 | `/problems/main-business-conflict` | No | Business conflict preserved in facade semantics |
| Upstream 422 | 422 | `/problems/main-business-rule-violated` | No | Business rule violation preserved in facade semantics |
| Upstream 5xx | 502 or 503 | `/problems/main-dependency-failed` | Yes | Use 502 vs 503 based on failure nature |

## 5.3 Optional Enrichment Errors

| Scenario | Overall HTTP Status | Problem Response | Behavior |
|---|---:|---|---|
| Enrichment timeout | 200 | No | Omit field, mark response as partial |
| Enrichment unavailable | 200 | No | Omit field, add warning |
| Enrichment returns 404 | 200 | No | Omit field, mark enrichment status as `NOT_FOUND` |
| Enrichment returns invalid JSON | 200 | No | Omit field, add warning |
| Enrichment returns 429 | 200 | No | Omit field, mark as rate-limited |

## 5.4 Orchestration Errors

| Scenario | HTTP Status | Problem Type | Retryable | Notes |
|---|---:|---|---:|---|
| Merge rule failed | 500 | `/problems/orchestration-merge-failed` | No | Internal composition error |
| Response invariant violated | 500 | `/problems/orchestration-invariant-violated` | No | Core response cannot be safely produced |
| Inconsistent required core data | 500 or 502 | `/problems/orchestration-invalid-core-data` | Sometimes | Depends on whether the problem is internal or caused by corrupted upstream data |
| Unexpected unhandled exception | 500 | `/problems/internal-processing-failed` | Unknown | General fallback |

---

## 6. Partial Success Policy

### 6.1 Rule
If the main flow succeeds and an optional enrichment fails:

- return `200 OK`,
- omit the failed enrichment field,
- set `meta.responseStatus = PARTIAL`,
- include `missingFields`,
- include a structured warning and enrichment status.

### 6.2 Why Not 206 Partial Content
`206 Partial Content` is primarily associated with HTTP range requests.
For an aggregation facade, `200 OK` with explicit partial-response metadata is clearer and more interoperable.

### 6.3 Recommended Enrichment Status Values

| Status | Meaning |
|---|---|
| `SUCCESS` | Enrichment loaded successfully |
| `SKIPPED` | Enrichment not applicable by business rule |
| `TIMEOUT` | Downstream did not respond in time |
| `UNAVAILABLE` | Downstream service is unavailable |
| `NOT_FOUND` | Enrichment data does not exist |
| `INVALID_RESPONSE` | Downstream response is malformed or unusable |
| `RATE_LIMITED` | Downstream returned 429 |
| `FAILED` | Generic fallback status |

---

## 7. Problem Type Catalog

## 7.1 Client Problems

| Type URI | Title | Recommended HTTP Status |
|---|---|---:|
| `/problems/request-invalid` | Request is invalid | 400 |
| `/problems/request-validation-failed` | Request validation failed | 400 / 422 |
| `/problems/unsupported-media-type` | Unsupported media type | 415 |
| `/problems/not-acceptable` | Representation not acceptable | 406 |
| `/problems/authentication-required` | Authentication required | 401 |
| `/problems/access-denied` | Access denied | 403 |
| `/problems/rate-limit-exceeded` | Rate limit exceeded | 429 |

## 7.2 Main Dependency Problems

| Type URI | Title | Recommended HTTP Status |
|---|---|---:|
| `/problems/main-resource-not-found` | Main resource not found | 404 |
| `/problems/main-business-conflict` | Main request conflicts with current state | 409 |
| `/problems/main-business-rule-violated` | Main request violates business rules | 422 |
| `/problems/main-dependency-unavailable` | Main dependency unavailable | 503 |
| `/problems/main-dependency-timeout` | Main dependency timed out | 504 |
| `/problems/main-dependency-invalid-response` | Main dependency returned an invalid response | 502 |
| `/problems/main-dependency-failed` | Main dependency failed | 502 / 503 |

## 7.3 Orchestration Problems

| Type URI | Title | Recommended HTTP Status |
|---|---|---:|
| `/problems/orchestration-merge-failed` | Response composition failed | 500 |
| `/problems/orchestration-invariant-violated` | Response invariant violated | 500 |
| `/problems/orchestration-invalid-core-data` | Core data is inconsistent | 500 / 502 |
| `/problems/internal-processing-failed` | Internal processing failed | 500 |

---

## 8. RFC 9457 Field Rules

## 8.1 `type`
Use a stable facade-defined URI.
This is the primary machine-readable identifier of the problem.

## 8.2 `title`
Use a short, stable summary.
Do not make it incident-specific.

Good examples:

- `Main dependency timed out`
- `Request validation failed`

## 8.3 `detail`
Use an incident-specific but safe explanation.
Do not leak internal hostnames, stack traces, or raw downstream payloads.

## 8.4 `instance`
Use the request URI, request path, or an incident URI.
It should help correlate the problem occurrence.

---

## 9. Recommended Problem Extensions

| Extension | Required | Purpose |
|---|---:|---|
| `errorCode` | Yes | Stable internal code for support and analytics |
| `traceId` | Yes | Correlation with logs and tracing |
| `category` | Yes | `CLIENT`, `MAIN_DEPENDENCY`, `ORCHESTRATION`, `PLATFORM` |
| `retryable` | Yes | Whether retry may succeed |
| `dependency` | No | Name of the downstream dependency |
| `violations` | For validation | Structured field-level validation details |
| `supportId` | Optional | Dedicated support-facing incident id |

---

## 10. Security and Information Exposure Rules

Do **not** expose the following in `detail` or extensions:

- stack traces,
- internal Java exception class names,
- private hostnames or internal URLs,
- raw third-party error bodies without filtering,
- tokens, secrets, or internal headers,
- SQL or infrastructure-level details.

---

## 11. Logging and Observability Policy

For every failure, including optional enrichment failures, log structured data:

| Field | Purpose |
|---|---|
| `traceId` | Correlation |
| endpoint + method | Request context |
| consumer/client id | Caller identification |
| main dependency status | Core flow diagnostics |
| enrichment attempts | Downstream enrichment diagnostics |
| latency per call | Performance analysis |
| retry count | Retry visibility |
| final classification | `SUCCESS`, `PARTIAL_SUCCESS`, `FAILURE` |
| final problem type and status | Contract visibility |

---

## 12. Decision Flow

### Step 1
Identify the source of the issue:

- client request,
- main dependency,
- optional enrichment,
- orchestration logic,
- platform.

### Step 2
If the issue is in optional enrichment:

- do not fail the request,
- omit the affected field,
- return partial-success metadata.

### Step 3
If the issue is in the main flow or core orchestration:

- classify it,
- map it to the facade problem catalog,
- return RFC 9457 problem details.

### Step 4
Always emit structured logs and tracing metadata.

---

## 13. Canonical Scenarios

### 13.1 Happy Path
- main call succeeds,
- all enrichments succeed,
- response is `200 OK`,
- `responseStatus = FULL`.

### 13.2 Main Resource Not Found
- main dependency returns not found,
- facade returns `404`,
- problem type: `/problems/main-resource-not-found`.

### 13.3 Main Dependency Timeout
- main dependency times out,
- facade returns `504`,
- problem type: `/problems/main-dependency-timeout`.

### 13.4 Main Dependency Invalid Response
- main dependency returns malformed JSON,
- facade returns `502`,
- problem type: `/problems/main-dependency-invalid-response`.

### 13.5 Optional Enrichment Timeout
- main flow succeeds,
- one enrichment times out,
- facade returns `200`,
- enrichment field is omitted,
- `responseStatus = PARTIAL`.

### 13.6 Multiple Optional Enrichment Failures
- main flow succeeds,
- multiple enrichments fail,
- facade returns `200`,
- all failed optional fields are omitted,
- metadata contains multiple warnings and statuses.

### 13.7 Conditional Enrichment Skipped
- main flow succeeds,
- enrichment not applicable by business rule,
- facade returns `200`,
- enrichment status is `SKIPPED`,
- overall response may still be considered `FULL`.

### 13.8 Orchestration Merge Failure
- downstream data is available,
- internal merge logic fails,
- facade returns `500`,
- problem type: `/problems/orchestration-merge-failed`.

---

## 14. Final Policy Summary

- The API is a **Domain Facade**.
- The main downstream flow is **mandatory**.
- Enrichment is **optional**.
- RFC 9457 is used only for **terminal failures**.
- Optional enrichment failures never fail the whole request.
- Partial responses are represented explicitly in the success payload.
- Only facade-defined problem types are exposed externally.
- All failures and degradations are logged in a structured way.

---

## 15. Recommended Next Step

Use this document as the baseline for:

1. an architecture review,
2. API standards documentation,
3. a team-wide error-handling guideline,
4. implementation rules for global exception handling,
5. OpenAPI documentation examples for both problem responses and partial success responses.
