# Aggregation Gateway

[![Java 21](https://img.shields.io/badge/Java-21-007396?logo=openjdk&logoColor=white)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot 4.0.6](https://img.shields.io/badge/Spring%20Boot-4.0.6-6DB33F?logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![Spring WebFlux](https://img.shields.io/badge/WebFlux-reactive-6DB33F?logo=spring&logoColor=white)](https://docs.spring.io/spring-framework/reference/web/webflux.html)
[![Gradle 9.4.1](https://img.shields.io/badge/Gradle-9.4.1-02303A?logo=gradle&logoColor=white)](https://gradle.org/)
[![Jackson 3.1.2](https://img.shields.io/badge/Jackson-3.1.2-2F6DB3)](https://github.com/FasterXML/jackson)
[![Renovate](https://img.shields.io/badge/Renovate-enabled-1A1F6C?logo=renovatebot&logoColor=white)](https://docs.renovatebot.com/)
[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=mabramenka_webflux&metric=alert_status&token=f775095566196b3449bd25c7a899aaf4204526ae)](https://sonarcloud.io/summary/new_code?id=mabramenka_webflux)
[![Coverage](https://sonarcloud.io/api/project_badges/measure?project=mabramenka_webflux&metric=coverage&token=f775095566196b3449bd25c7a899aaf4204526ae)](https://sonarcloud.io/summary/new_code?id=mabramenka_webflux)
[![Bugs](https://sonarcloud.io/api/project_badges/measure?project=mabramenka_webflux&metric=bugs&token=f775095566196b3449bd25c7a899aaf4204526ae)](https://sonarcloud.io/summary/new_code?id=mabramenka_webflux)
[![Code Smells](https://sonarcloud.io/api/project_badges/measure?project=mabramenka_webflux&metric=code_smells&token=f775095566196b3449bd25c7a899aaf4204526ae)](https://sonarcloud.io/summary/new_code?id=mabramenka_webflux)
[![Duplicated Lines (%)](https://sonarcloud.io/api/project_badges/measure?project=mabramenka_webflux&metric=duplicated_lines_density&token=f775095566196b3449bd25c7a899aaf4204526ae)](https://sonarcloud.io/summary/new_code?id=mabramenka_webflux)

Reactive Spring Boot service that calls an account group service, executes selected JSON aggregation parts by dependency levels, and merges their results back into the account group JSON document.

The service keeps downstream payloads dynamic by working with Jackson `JsonNode` / `ObjectNode` instead of fixed response DTOs.

## Highlights

- Dynamic JSON aggregation without fixed downstream response DTOs
- Spring Boot 4 HTTP service clients backed by reactive `WebClient`
- Dependency-ordered aggregation parts with per-part `APPLIED` / `EMPTY` / `SKIPPED` / `FAILED` outcomes and `REQUIRED` / `OPTIONAL` criticality
- Declarative path-based enrichment rules for keyed array joins
- Fallback key paths for inconsistent downstream schemas
- Recursive beneficial-owners aggregation with bounded depth
- Header and query parameter forwarding to downstream calls
- RFC 9457-style problem responses for validation and downstream failures
- Actuator health, readiness, liveness, info, and metrics endpoints
- Aggregation part outcome metrics
- JSpecify nullability annotations checked by NullAway
- Spotless, JaCoCo, OWASP Dependency Check, and SonarQube Cloud wiring
- Renovate-ready dependency maintenance

## Stack

- Java 21
- Spring Boot 4.0.6
- Spring Framework 7.0.7
- Spring WebFlux
- Spring HTTP service clients
- Jackson 3.1.2
- Gradle 9.4.1 with version catalog
- JUnit 5, Reactor Test, Mockito, AssertJ
- Lombok, Error Prone, NullAway
- Spotless, JaCoCo, OWASP Dependency Check
- Renovate and SonarQube Cloud

## Run

The service listens on HTTPS (`:8443`) by default and calls downstreams over HTTPS as well, both
configured through Spring Boot SSL Bundles (`spring.ssl.bundle.pem.server` for inbound,
`spring.ssl.bundle.pem.downstream` for outbound trust). Before the first `bootRun`, generate a
self-signed dev cert:

```bash
./scripts/gen-dev-certs.sh
./gradlew bootRun
```

The generated PEM files land in `src/main/resources/certs/dev/` and are gitignored. CI and
production must supply their own bundles via mounted secrets and environment-specific
`application-*.yaml` overlays; do not commit key material.

Default client settings live in [application.yaml](src/main/resources/application.yaml):

```yaml
server:
  port: 8443
  ssl:
    bundle: server
spring:
  ssl:
    bundle:
      pem:
        server:
          keystore:
            certificate: "classpath:certs/dev/server.crt"
            private-key: "classpath:certs/dev/server.key"
        downstream:
          truststore:
            certificate: "classpath:certs/dev/downstream-ca.crt"
  http:
    clients:
      connect-timeout: 2s
      read-timeout: 5s
      reactive:
        connector: reactor
    serviceclient:
      account-group:
        base-url: https://localhost:8081
        read-timeout: 2s
      account:
        base-url: https://localhost:8083
        read-timeout: 3s
      owners:
        base-url: https://localhost:8084
        read-timeout: 3s
```

Override these properties with standard Spring configuration when running in another environment.

## Downstream Services

The application registers three HTTP service client groups:

| Group | Interface | Method | Path | Default base URL |
| --- | --- | --- | --- | --- |
| `account-group` | `AccountGroups` | `POST` | `/account-groups` | `https://localhost:8081` |
| `account` | `Accounts` | `POST` | `/accounts` | `https://localhost:8083` |
| `owners` | `Owners` | `POST` | `/owners` | `https://localhost:8084` |

All downstream clients send and accept JSON. Downstream 4xx/5xx responses, transport failures, and unreadable payloads are normalized into the facade-owned RFC 9457 problem catalog.

## Architecture

Aggregation Gateway is a synchronous domain facade, not an owner of account, account-group, or owner data. Its boundary is response composition: it validates the caller request, calls the mandatory account-group dependency, expands the selected aggregation graph, runs the selected and dependency-enabled enrichment paths, and returns one merged JSON document.

### Module Boundaries

| Package | Responsibility | Owns |
| --- | --- | --- |
| `api` | HTTP endpoints and transport DTOs | Public request shape and route versioning |
| `service` | Request orchestration entry point | Account-group request construction and top-level observation |
| `part` | Aggregation graph planning, dependency levels, execution, merge application, metrics | Selected-part execution semantics and per-part outcomes |
| `enrichment.<name>` | Business-specific enrichment parts | Part name, dependency declaration, downstream request and merge rule |
| `enrichment.support` | Reusable enrichment mechanics | Path selection, keyed joins, and tolerant keyed-response attachment |
| `client` | Spring HTTP service clients and downstream error filter | Outbound paths, forwarded context, HTTP-layer failure normalization |
| `error` | Problem Detail mapping | Stable validation and known domain/downstream problem shapes |
| `config` | WebFlux, client, SSL, MDC, and context propagation wiring | Runtime plumbing and framework integration |

### Runtime Flow

1. `api` validates the inbound POST body or GET path/query arguments.
2. `config` builds `ClientRequestContext` from selected inbound headers and query parameters.
3. `service` plans selected parts before calling downstreams, so unknown part names fail before any account-group call.
4. `service` calls the mandatory account-group downstream and requires an object-shaped JSON response.
5. `part` expands dependencies, groups parts by dependency level, and validates every selected part against the current root snapshot.
6. A selected part whose `supports(context)` is false is recorded under `meta.parts` as `SKIPPED` with reason `UNSUPPORTED_CONTEXT`.
7. Runnable parts in the same level execute concurrently; the next level starts only after successful results are applied in graph order.
8. Data absence is soft per part: `NO_KEYS_IN_MAIN`, `DOWNSTREAM_EMPTY`, `DOWNSTREAM_NOT_FOUND`, `UNSUPPORTED_CONTEXT`, and `DEPENDENCY_EMPTY` succeed with `meta.parts`; transport/auth/timeout/invalid-payload/merge failures still terminate the request unless the part opts in to `OPTIONAL` criticality, in which case its `DownstreamClientException` is recorded as `meta.parts.<name> = { status: FAILED, criticality: OPTIONAL, reason, errorCode }` and the request continues. Orchestration/merge/invariant failures always propagate regardless of criticality.

### Consistency And Failure Semantics

- The account-group call is the mandatory root. If it fails or returns unreadable/non-object JSON, the whole request fails.
- `include == null` selects every registered part. `include == []` returns only the account-group response.
- Explicitly selected transitive dependencies are enabled automatically. For example, `beneficialOwners` also selects `owners`.
- Keyed enrichments (`account`, `owners`) request every distinct key found in the root payload and attach only the entries that are actually returned.
- Same-level part results are generated from the same immutable root snapshot, then merged into the mutable root in stable graph order.
- The service does not persist state and has no distributed transaction. Consistency is per request and depends on downstream responses at request time.

### Error Contract Boundaries

The implemented public error contract follows [error-handling-design.md](error-handling-design.md): every error response is a facade-owned RFC 9457 Problem Detail with stable `type`, `errorCode`, `category`, `retryable`, `traceId`, `timestamp`, and `instance` fields. Validation failures, downstream dependency failures, enrichment contract violations, orchestration failures, framework 4xx errors, overload failures, and unexpected platform failures are all normalized through this catalog.

Selected enrichment failures are no longer anonymous internal errors. Missing keys in the main payload, empty enrichment bodies, `404` enrichment responses, unsupported contexts, and empty dependencies are success-side `meta.parts` outcomes; nested beneficial-owners contract violations still map to `/problems/enrichment/contract-violation`; merge failures map to `/problems/orchestration/merge-failed`; unclassified unchecked failures map to `/problems/platform/internal`.

Raw downstream `404` responses from the main dependency are treated as opaque dependency failures unless facade code explicitly classifies the condition as a domain not-found outcome. Top-level enrichment `404` responses are converted to `meta.parts.<name> = { status: EMPTY, reason: DOWNSTREAM_NOT_FOUND }`. `ORCH-CONFIG-INVALID` is reserved for runtime configuration failures detected after startup; configuration errors found during bean creation may fail application startup before an HTTP response exists.

## API

The URL path carries an explicit API version segment; the controller binds `/api/{apiVersion}/aggregate` to version `1` (the framework validates the segment against the supported versions list, so `v1` is accepted while `v2` is rejected with `404`).

OpenAPI 3 spec and Swagger UI are served under the same versioned prefix:

- spec: `GET /api/v1/v3/api-docs`
- UI: `GET /api/v1/swagger-ui.html`

The UI's "Authorize" dialog accepts an `Authorization` header value that is forwarded verbatim to downstream services (no `Bearer ` prefix is added).

```http
POST /api/v1/aggregate
Content-Type: application/json
Accept: application/json
```

```http
GET /api/v1/aggregate/{id}
Accept: application/json
```

The `GET` form is a convenience that accepts a single `id` path variable (validated against the shared id pattern) plus an optional repeated `include` query parameter. It reuses the same aggregation pipeline as `POST`.

### Request Body

```json
{
  "ids": ["id-x19"],
  "include": ["account", "owners"]
}
```

Fields sent to the account group service:

- `ids`: required non-empty array of non-blank, pattern-matching strings (bounded by `AccountGroupIds.MAX_PER_REQUEST`)

`include` controls aggregation parts:

- omitted or `null`: all registered parts are selected
- empty array: only the account group response is returned
- supported values: `account`, `owners`, `beneficialOwners`
- unknown values fail before calling the account group service
- selected parts may finish as `APPLIED`, `EMPTY`, `SKIPPED`, or `FAILED` in `meta.parts`; each entry carries its `criticality` (`REQUIRED` by default; `OPTIONAL` is opt-in per part)
- dependency transport/auth/timeout/invalid-payload failures fail the request for `REQUIRED` parts and are recorded as `FAILED` outcomes for `OPTIONAL` parts; merge/invariant failures always fail the request

### Query Parameters

```http
POST /api/v1/aggregate?detokenize=true
```

`detokenize` is optional and must be `true` or `false`. When present, it is forwarded to downstream client calls.

### Forwarded Headers

The service forwards selected inbound headers to downstream services:

- `Authorization`
- `X-Request-Id`
- `X-Correlation-Id`
- `Accept-Language`

## Error Responses

All failures are returned as RFC 9457 `application/problem+json` responses. The public contract is the catalog in [error-handling-design.md](error-handling-design.md); clients should use `errorCode` or `type` as machine-readable identifiers and must not parse `detail`.

Every problem response contains:

- `type`, `title`, `status`, `detail`, `instance`
- `errorCode`
- `category`
- `traceId`
- `retryable`
- `timestamp`

Dependency errors also include `dependency` with a logical value such as `main`, `enricher:account`, or `enricher:owners`. Client validation errors include `violations`, where each item has a JSON-pointer-like `pointer` and a generic `message`.

Every response, success or error, carries a valid W3C `traceparent` header. If the request supplied a valid `traceparent`, it is echoed; otherwise the service generates one. Problem responses expose the trace id portion as `traceId`.

### 400 Bad Request — validation

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
    {"pointer": "/ids", "message": "must not be empty"}
  ]
}
```

Bean validation failures, malformed JSON, invalid query/path values, and unknown `include` values all use this catalog entry. Framework routing and negotiation failures use dedicated catalog entries such as `/problems/method-not-allowed`, `/problems/not-acceptable`, and `/problems/unsupported-media`.

### 502 Bad Gateway — enrichment contract violation

```json
{
  "type": "/problems/enrichment/contract-violation",
  "title": "Enrichment dependency payload violates contract",
  "status": 502,
  "detail": "A required enrichment dependency payload does not satisfy the required contract.",
  "instance": "/requests/0af7651916cd43dd8448eb211c80319c",
  "errorCode": "ENRICH-CONTRACT-VIOLATION",
  "category": "ENRICHMENT_DEPENDENCY",
  "dependency": "enricher:account",
  "traceId": "0af7651916cd43dd8448eb211c80319c",
  "retryable": false,
  "timestamp": "2026-04-22T10:15:30Z"
}
```

This is emitted, for example, when the nested beneficial-owners resolver receives malformed or incomplete owners payloads while walking the ownership tree.

### 502/504 — downstream dependency failure

```json
{
  "type": "/problems/main/invalid-payload",
  "title": "Main dependency returned an invalid payload",
  "status": 502,
  "detail": "The main dependency returned a payload that could not be read.",
  "instance": "/requests/4bf92f3577b34da6a3ce929d0e0e4736",
  "errorCode": "MAIN-INVALID-PAYLOAD",
  "category": "MAIN_DEPENDENCY",
  "dependency": "main",
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
  "retryable": false,
  "timestamp": "2026-04-22T10:15:30Z"
}
```

Timeout and unavailable dependency failures use `504` and `retryable: true`; bad statuses, invalid payloads, authentication failures to dependencies, and contract violations use `502` and `retryable: false`.

### 500 Internal Server Error — orchestration/platform

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

Unclassified exceptions use `/problems/platform/internal` with `errorCode: PLATFORM-INTERNAL`.

## Aggregation Flow

1. Validate the inbound request and `include` selection.
2. Build and send the account group request to `/account-groups`.
3. Expand aggregation part dependencies for the requested `include` set.
4. Build dependency levels from the selected aggregation parts.
5. For each level, evaluate whether each selected part can run against the current root snapshot or should yield a soft `meta.parts` outcome.
6. Execute runnable parts in the same level in parallel; fatal dependency, decoding, merge, or invariant failures fail the request.
7. Apply successful results in stable graph order before the next dependency level starts.
8. Record non-fatal no-op outcomes in `meta.parts` and skip dependents with `DEPENDENCY_EMPTY` when a prerequisite part did not apply.

Default part dependency order:

1. `account` and `owners` can run in parallel.
2. `beneficialOwners` waits for `owners`, then runs against the merged owner data.

## Aggregation Parts

### Account

Reads account ids from the account group response:

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

Appends matched response entries to the owning `data[*]` item under:

```text
account1
```

### Owners

Reads owner ids from the account group response with fallback fields:

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

Indexes owners response entries with fallback fields:

```text
$.data[*].individual.number
$.data[*].id
```

Appends matched response entries to the owning `data[*]` item under:

```text
owners1
```

### Beneficial Owners

Resolves the ownership tree rooted at entity owners already merged under `owners1`.

For every `data[*]` item, the part walks each entry in `owners1` whose shape identifies an entity (non-individual) and repeatedly fetches `/owners` in level-by-level batches, following the child numbers on each resolved entity. Individuals encountered anywhere in the tree are collected (deduplicated by number, first-seen order) and attached to the owning entity under:

```text
beneficialOwnersDetails
```

Traversal is bounded by a maximum depth of 6 levels. Downstream failures, malformed responses, or depth violations fail the selected `beneficialOwners` part and therefore fail the request.

Requested via `include: ["beneficialOwners"]` (or by omitting `include`). The part name also participates in the unknown-name validation that is applied to the `include` set.

## Workflow Binding Paths

Workflow-based enrichments are authored using `WorkflowAggregationPart` + `AggregationWorkflow` and
step classes such as `KeyedBindingStep`, `ComputeStep`, `RecursiveFetchStep`, and `TraversalReducerStep`.

For end-to-end authoring guidance and examples, see:

```text
docs/workflow-enrichment-guide.md
```

The path/key dialect used by workflow binding support (`KeyExtractor`, `ResponseIndexer`, `KeyedBindingStep`)
is intentionally small:

```text
$
$.field
$.field[*]
$.field[*].nested.field
```

This is JSONPath-like syntax, not a full JSONPath engine. Filters, slices, indexes, bracket notation, and recursive descent are not supported.

## Operations

The service exposes selected Actuator endpoints:

- `/actuator/health`
- `/actuator/health/liveness`
- `/actuator/health/readiness`
- `/actuator/info`
- `/actuator/metrics`

All other Actuator endpoints are disabled by default.

Custom metric names:

- `aggregation.request`, tagged by `part_selection` and `requested_parts`
- `aggregation.part.requests`, tagged by `part` and `outcome` (emitted by aggregation parts)
- `aggregation.beneficial_owners.tree`, tagged by `outcome` (per root entity resolved by the beneficial-owners part)

## Quality Gates

Large suites are grouped by scenario:

- `AggregateServiceSelectionTest`, `AggregateServiceDependencyTest`, `AggregateServiceJoinTest`
- `AggregateControllerRequestValidationTest`, `AggregateControllerProblemMappingTest`, `AggregateControllerRoutingTest`

Run unit and integration tests:

```bash
./gradlew test
```

Run formatting checks, tests, classpath verification, and JaCoCo:

```bash
./gradlew check
```

OWASP Dependency Check is intentionally not part of the default `check` task because it is slow and rate-limited without an NVD API key. Run it explicitly when `NVD_API_KEY` is available:

```bash
export NVD_API_KEY=...
./gradlew securityCheck
```

Verify that runtime classpaths stay on the Spring Boot 4 / Spring Framework 7 / Jackson 3 line:

```bash
./gradlew verifyBoot4Classpath
```

Apply formatting:

```bash
./gradlew spotlessApply
```

Generate JaCoCo XML and HTML coverage reports:

```bash
./gradlew jacocoTestReport
```

Reports are written under `build/reports/`.

The Java compile tasks fail on deprecation and removal warnings, keeping major-version migration issues visible during normal builds.

## Renovate

Renovate is configured in [renovate.json](renovate.json):

- best-practices preset
- dependency dashboard
- dependency PR label
- Spring Boot plugin updates grouped; the plugin version supplies the BOM coordinates
- Lombok updates grouped together

Enable the Renovate GitHub App for the repository to start receiving dependency update PRs.

## SonarQube Cloud

SonarQube Cloud is wired through Gradle.

Gradle tasks:

```bash
./gradlew test jacocoTestReport
./gradlew sonar
```

Default project settings are in [build.gradle.kts](build.gradle.kts):

```properties
sonar.projectKey=mabramenka_webflux
sonar.organization=mabramenka
sonar.host.url=https://sonarcloud.io
```

The Sonar task requires a token:

```text
SONAR_TOKEN
```

Create the token in SonarQube Cloud, then add it to the environment used by CI or local analysis.
