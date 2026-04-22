# Aggregation Gateway

[![Java 21](https://img.shields.io/badge/Java-21-007396?logo=openjdk&logoColor=white)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot 4.0.5](https://img.shields.io/badge/Spring%20Boot-4.0.5-6DB33F?logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![Spring WebFlux](https://img.shields.io/badge/WebFlux-reactive-6DB33F?logo=spring&logoColor=white)](https://docs.spring.io/spring-framework/reference/web/webflux.html)
[![Gradle 9.4.1](https://img.shields.io/badge/Gradle-9.4.1-02303A?logo=gradle&logoColor=white)](https://gradle.org/)
[![Jackson 3.1.2](https://img.shields.io/badge/Jackson-3.1.2-2F6DB3)](https://github.com/FasterXML/jackson)
[![Renovate](https://img.shields.io/badge/Renovate-enabled-1A1F6C?logo=renovatebot&logoColor=white)](https://docs.renovatebot.com/)
[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=mabramenka_webflux&metric=alert_status&token=f775095566196b3449bd25c7a899aaf4204526ae)](https://sonarcloud.io/summary/new_code?id=mabramenka_webflux)
[![Coverage](https://sonarcloud.io/api/project_badges/measure?project=mabramenka_webflux&metric=coverage&token=f775095566196b3449bd25c7a899aaf4204526ae)](https://sonarcloud.io/summary/new_code?id=mabramenka_webflux)
[![Bugs](https://sonarcloud.io/api/project_badges/measure?project=mabramenka_webflux&metric=bugs&token=f775095566196b3449bd25c7a899aaf4204526ae)](https://sonarcloud.io/summary/new_code?id=mabramenka_webflux)
[![Code Smells](https://sonarcloud.io/api/project_badges/measure?project=mabramenka_webflux&metric=code_smells&token=f775095566196b3449bd25c7a899aaf4204526ae)](https://sonarcloud.io/summary/new_code?id=mabramenka_webflux)
[![Duplicated Lines (%)](https://sonarcloud.io/api/project_badges/measure?project=mabramenka_webflux&metric=duplicated_lines_density&token=f775095566196b3449bd25c7a899aaf4204526ae)](https://sonarcloud.io/summary/new_code?id=mabramenka_webflux)

Reactive Spring Boot service that calls an account group service, executes optional JSON aggregation parts by dependency levels, and merges successful optional results back into the account group JSON document.

The service keeps downstream payloads dynamic by working with Jackson `JsonNode` / `ObjectNode` instead of fixed response DTOs.

## Highlights

- Dynamic JSON aggregation without fixed downstream response DTOs
- Spring Boot 4 HTTP service clients backed by reactive `WebClient`
- Dependency-ordered optional aggregation parts with per-part failure isolation
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
- Spring Boot 4.0.5
- Spring Framework 7.0.x managed by the Spring Boot BOM
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

All downstream clients send and accept JSON. Downstream 4xx/5xx responses, transport failures, and unreadable account group responses are mapped to `502 Bad Gateway` problem responses.

## API

The URL path carries an explicit API version segment; the controller binds `/api/{apiVersion:v\d+}/aggregate` to version `1`.

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

`include` controls optional aggregation parts:

- omitted or `null`: all registered parts are enabled
- empty array: only the account group response is returned
- supported values: `account`, `owners`, `beneficialOwners`
- unknown values fail before calling the account group service

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

All failures are returned as RFC 7807 `application/problem+json` responses. Only the canonical RFC 7807 fields (`type`, `title`, `status`, `detail`, `instance`) are standard; a small set of documented extension members is added where useful. The `title` is derived from the HTTP status reason phrase — it is not a custom, stable identifier. Use the `type` URI as the machine-readable problem identifier.

When the inbound request carries `X-Request-Id` or `X-Correlation-Id`, or the response pipeline has assigned a request id, those values are echoed back as `requestId` and `correlationId` extension members on every problem response.

### 400 Bad Request — request validation failed

Emitted when bean validation rejects the `@RequestBody` payload, when method-level validation rejects a path/query/header argument, or when a handler programmatically throws `RequestValidationException` (for example, an invalid `detokenize` value).

```json
{
  "type": "/problems/request-validation-failed",
  "title": "Bad Request",
  "status": 400,
  "detail": "Invalid request content.",
  "errors": [
    {"location": "body", "field": "ids", "message": "must not be empty"}
  ]
}
```

The `errors` array is always present for this problem type. Each entry has `location` (`body`, `path`, `query`, `header`, or `request`), `field` (nullable for global errors), and `message`. The `detail` varies by source: `"Invalid request content."` for body binding, `"Request validation failed."` for method-argument validation, and the programmatic message for `RequestValidationException`.

### 400 Bad Request — malformed request content

Emitted for unreadable or malformed inputs below the validation layer (for example, a non-JSON body or a missing required value that Spring raises as `ServerWebInputException`).

```json
{
  "type": "/problems/invalid-request-content",
  "title": "Bad Request",
  "status": 400,
  "detail": "Request content is malformed or unreadable."
}
```

No `errors` array is emitted for this type.

### 422 Unprocessable Content — unsupported aggregation part name

Emitted when `include` contains a name that is not a registered aggregation part. Validation runs before the account group service is called.

```json
{
  "type": "/problems/unsupported-aggregation-part",
  "title": "Unprocessable Content",
  "status": 422,
  "detail": "Unsupported aggregation part(s): foo",
  "parts": ["foo"]
}
```

### 502 Bad Gateway — downstream client error

Emitted when a downstream call fails. Two shapes are possible, differentiated by whether an HTTP status was received.

Upstream returned a 4xx/5xx response:

```json
{
  "type": "/problems/downstream-client-error",
  "title": "Bad Gateway",
  "status": 502,
  "detail": "Account group client returned an error response",
  "client": "Account group",
  "downstreamStatus": 503
}
```

Transport failure, unreadable body, or other non-status error:

```json
{
  "type": "/problems/downstream-client-error",
  "title": "Bad Gateway",
  "status": 502,
  "detail": "Account group client request failed",
  "client": "Account group"
}
```

`client` is the human-readable client name derived from the HTTP service group. `downstreamStatus` is present only when the upstream returned a status code.

### 500 Internal Server Error — unhandled error

Returned by the catch-all handler for any exception not mapped by a more specific handler. The logs retain the stack trace; the response body is deliberately generic.

```json
{
  "type": "/problems/internal-aggregation-error",
  "title": "Internal Server Error",
  "status": 500,
  "detail": "The aggregation request could not be completed."
}
```

## Aggregation Flow

1. Validate the inbound request and `include` selection.
2. Build and send the account group request to `/account-groups`.
3. Expand aggregation part dependencies for the requested `include` set.
4. Build dependency levels from the selected aggregation parts.
5. For each level, evaluate `supports(context)` against the current root snapshot.
6. Execute supported parts in the same level in parallel with per-part failure isolation.
7. Apply successful results in stable graph order before the next dependency level starts.
8. Skip parts whose dependencies did not produce an applied result.

Default optional part dependency order:

1. `account` and `owners` can run in parallel.
2. `beneficialOwners` waits for `owners`, then runs against the merged owner data.

## Optional Parts

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

Traversal is bounded by a maximum depth of 6 levels. Downstream failures, malformed responses, or depth violations abort the tree for that entity only; the enclosing `data[*]` item keeps all previously merged data.

Requested via `include: ["beneficialOwners"]` (or by omitting `include`). The part name also participates in the unknown-name validation that is applied to the `include` set.

## Enrichment Rules

Business-specific enrichment parts live under `enrichment.<name>`. Shared keyed-join mechanics live under
`enrichment.support.keyed`.

Keyed enrichment parts are configured with `EnrichmentRule`:

```java
private static final EnrichmentRule ENRICHMENT_RULE = EnrichmentRule.builder()
    .mainItems("$.data[*]", "basicDetails.owners[*].id", "basicDetails.owners[*].number")
    .responseItems("$.data[*]", "individual.number", "id")
    .requestKeysField("ids")
    .targetField("owners1")
    .build();
```

Rule semantics:

- `mainItems` selects the item level in the account group response.
- main key paths are relative to each selected item.
- response key paths are relative to each selected response item.
- key paths are tried in order, so later paths are fallback values.
- request ids are deduplicated while merge still runs for every matching item.
- matched response entries are appended as whole JSON elements.

Supported path syntax is intentionally small:

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
- `aggregation.part.requests`, tagged by `part` and `outcome` (emitted by optional aggregation parts)
- `aggregation.beneficial_owners.tree`, tagged by `outcome` (per root entity resolved by the beneficial-owners part)

## Quality Gates

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
