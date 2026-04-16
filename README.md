# WebFlux JSON Aggregation

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

Reactive Spring Boot service that calls an account group service, optionally fetches additional JSON parts in parallel, and merges successful optional responses back into the account group JSON document.

The service keeps downstream payloads dynamic by working with Jackson `JsonNode` / `ObjectNode` instead of fixed response DTOs.

## Highlights

- Dynamic JSON aggregation without fixed downstream response DTOs
- Spring Boot 4 HTTP service clients backed by reactive `WebClient`
- Parallel optional enrichment parts with per-part failure isolation
- Declarative path-based enrichment rules for keyed array joins
- Fallback key paths for inconsistent downstream schemas
- Header and query parameter forwarding to downstream calls
- RFC 9457-style problem responses for validation and downstream failures
- Actuator health, readiness, liveness, info, and metrics endpoints
- Enrichment outcome metrics
- JSpecify nullability annotations checked by NullAway
- Spotless, JaCoCo, OWASP Dependency Check, and SonarQube Cloud wiring
- Renovate-ready dependency maintenance

## Stack

- Java 21
- Spring Boot 4.0.5
- Spring WebFlux
- Spring HTTP service clients
- Jackson 3.1.2
- Gradle 9.4.1 with version catalog
- JUnit 5, Reactor Test, Mockito, AssertJ
- Lombok, Error Prone, NullAway
- Spotless, JaCoCo, OWASP Dependency Check
- Renovate and SonarQube Cloud

## Run

```bash
./gradlew bootRun
```

Default client settings are configured in [application.properties](src/main/resources/application.properties):

```properties
spring.http.clients.connect-timeout=2s
spring.http.clients.read-timeout=5s
spring.http.clients.reactive.connector=reactor
spring.webflux.problemdetails.enabled=true
spring.reactor.context-propagation=auto

spring.http.serviceclient.account-group.base-url=http://localhost:8081
spring.http.serviceclient.account-group.read-timeout=2s
spring.http.serviceclient.account.base-url=http://localhost:8083
spring.http.serviceclient.account.read-timeout=3s
spring.http.serviceclient.owners.base-url=http://localhost:8084
spring.http.serviceclient.owners.read-timeout=3s
```

Override these properties with standard Spring configuration when running in another environment.

## Downstream Services

The application registers three HTTP service client groups:

| Group | Interface | Method | Path | Default base URL |
| --- | --- | --- | --- | --- |
| `account-group` | `AccountGroups` | `POST` | `/account-groups` | `http://localhost:8081` |
| `account` | `Accounts` | `POST` | `/accounts` | `http://localhost:8083` |
| `owners` | `Owners` | `POST` | `/owners` | `http://localhost:8084` |

All downstream clients send and accept JSON. Downstream 4xx/5xx responses, transport failures, and unreadable account group responses are mapped to `502 Bad Gateway` problem responses.

## API

```http
POST /api/v1/aggregate
Content-Type: application/json
Accept: application/json
```

### Request Body

```json
{
  "customerId": "cust-1",
  "market": "US",
  "includeItems": true,
  "include": ["account", "owners"]
}
```

Fields sent to the account group service:

- `customerId`: required non-blank string
- `market`: optional non-blank string, defaults to `US`
- `includeItems`: optional boolean, defaults to `true`

`include` controls optional aggregation parts:

- omitted or `null`: all registered parts are enabled
- empty array: only the account group response is returned
- supported values: `account`, `owners`
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

Validation failures return `400 Bad Request` with `application/problem+json`.

Example:

```json
{
  "type": "/problems/invalid-aggregation-request",
  "title": "Invalid aggregation request",
  "status": 400,
  "detail": "'customerId' is required"
}
```

Downstream failures return `502 Bad Gateway` with client metadata.

Example:

```json
{
  "type": "/problems/downstream-client-error",
  "title": "Downstream client error",
  "status": 502,
  "detail": "Account group client failed: account group client returned an unreadable response",
  "client": "Account group"
}
```

Internal aggregation errors return `500 Internal Server Error`.

## Aggregation Flow

1. Validate the inbound request and `include` selection.
2. Build and send the account group request to `/account-groups`.
3. Select registered optional parts requested by `include`.
4. Skip optional parts that do not support the returned account group shape.
5. Fetch enabled optional parts in parallel.
6. Ignore failed optional parts and keep the account group response.
7. Merge successful optional responses in registered order.

Optional part order:

1. `account`
2. `owners`

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

## Enrichment Rules

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

- `aggregation.request`, tagged by `enrichment_selection` and `requested_enrichments`
- `aggregation.enrichment.requests`, tagged by `enrichment` and `outcome`

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

Verify that runtime classpaths stay on the Spring Boot 4 / Jackson 3 line:

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
- Spring Boot plugin and BOM grouped together
- Lombok updates grouped together

Enable the Renovate GitHub App for the repository to start receiving dependency update PRs.

## SonarQube Cloud

SonarQube Cloud is wired through Gradle.

Gradle tasks:

```bash
./gradlew test jacocoTestReport
./gradlew sonar
```

Default project settings are in [build.gradle](build.gradle):

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
