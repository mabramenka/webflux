# WebFlux JSON Aggregation

[![Java 21](https://img.shields.io/badge/Java-21-007396?logo=openjdk&logoColor=white)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot 4](https://img.shields.io/badge/Spring%20Boot-4.0-6DB33F?logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![Spring WebFlux](https://img.shields.io/badge/WebFlux-reactive-6DB33F?logo=spring&logoColor=white)](https://docs.spring.io/spring-framework/reference/web/webflux.html)
[![Gradle](https://img.shields.io/badge/Gradle-version%20catalog-02303A?logo=gradle&logoColor=white)](https://docs.gradle.org/current/userguide/version_catalogs.html)
[![Jackson 3](https://img.shields.io/badge/Jackson-3.x-2F6DB3)](https://github.com/FasterXML/jackson)
[![Renovate](https://img.shields.io/badge/Renovate-enabled-1A1F6C?logo=renovatebot&logoColor=white)](https://docs.renovatebot.com/)
[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=mabramenka_webflux&metric=alert_status&token=f775095566196b3449bd25c7a899aaf4204526ae)](https://sonarcloud.io/summary/new_code?id=mabramenka_webflux)
[![Coverage](https://sonarcloud.io/api/project_badges/measure?project=mabramenka_webflux&metric=coverage&token=f775095566196b3449bd25c7a899aaf4204526ae)](https://sonarcloud.io/summary/new_code?id=mabramenka_webflux)
[![Bugs](https://sonarcloud.io/api/project_badges/measure?project=mabramenka_webflux&metric=bugs&token=f775095566196b3449bd25c7a899aaf4204526ae)](https://sonarcloud.io/summary/new_code?id=mabramenka_webflux)
[![Code Smells](https://sonarcloud.io/api/project_badges/measure?project=mabramenka_webflux&metric=code_smells&token=f775095566196b3449bd25c7a899aaf4204526ae)](https://sonarcloud.io/summary/new_code?id=mabramenka_webflux)
[![Duplicated Lines (%)](https://sonarcloud.io/api/project_badges/measure?project=mabramenka_webflux&metric=duplicated_lines_density&token=f775095566196b3449bd25c7a899aaf4204526ae)](https://sonarcloud.io/summary/new_code?id=mabramenka_webflux)

Reactive Spring Boot service that calls a main downstream service, optionally fetches additional JSON parts in parallel, and merges successful optional responses back into the main JSON document.

The service keeps downstream payloads dynamic by working with Jackson `JsonNode` / `ObjectNode` instead of fixed response DTOs.

## Highlights

- Dynamic JSON aggregation without fixed downstream response DTOs
- Parallel optional enrichment parts with failure isolation
- Declarative path-based enrichment rules
- Fallback key paths for inconsistent downstream schemas
- Header and query parameter forwarding for downstream calls
- CI-ready test, coverage, and SonarQube Cloud analysis
- Renovate-ready dependency maintenance

## Stack

- Java 21
- Spring Boot 4
- Spring WebFlux
- Jackson 3
- Gradle version catalog
- JUnit 5, Reactor Test, Mockito, AssertJ
- Renovate

## Run

```bash
./gradlew bootRun
```

Default downstream URLs are configured in [application.properties](src/main/resources/application.properties):

```properties
downstream.main.base-url=http://localhost:8081
downstream.profile.base-url=http://localhost:8082
downstream.pricing.base-url=http://localhost:8083
downstream.owners.base-url=http://localhost:8084
```

Override them with Spring configuration when running in another environment.

## Test

```bash
./gradlew test
```

Generate the JaCoCo XML and HTML coverage reports:

```bash
./gradlew jacocoTestReport
```

## API

```http
POST /api/v1/aggregate
Content-Type: application/json
```

### Request Body

```json
{
  "customerId": "cust-1",
  "market": "US",
  "includeItems": true,
  "include": ["profile", "pricing", "owners"]
}
```

Fields sent to the main downstream service:

- `customerId`
- `market`, default `US`
- `includeItems`, default `true`

`include` controls optional aggregation parts:

- omitted or `null`: all registered parts are enabled
- empty array: only the main response is returned
- supported values: `profile`, `pricing`, `owners`
- unknown values fail before calling the main downstream service

### Query Parameters

```http
POST /api/v1/aggregate?detokenize=true
```

`detokenize` is optional and must be `true` or `false`. When present, it is forwarded to downstream WebClient calls.

### Forwarded Headers

The service forwards selected inbound headers to downstream services:

- `Authorization`
- `X-Request-Id`
- `X-Correlation-Id`
- `Accept-Language`

## Aggregation Flow

1. Build and send the main request to `/main`.
2. Read `include` and select registered optional parts.
3. Skip optional parts that do not support the main response shape.
4. Fetch enabled optional parts in parallel.
5. Ignore failed optional parts and keep the main response.
6. Merge successful optional responses in registered order.

Optional part order:

1. `profile`
2. `pricing`
3. `owners`

## Optional Parts

### Profile

Request is built from the main response:

```json
{
  "customerId": "cust-1",
  "market": "US"
}
```

The profile response is embedded into the root response under:

```text
customerProfile
```

### Pricing

Reads account ids from the main response:

```text
$.data[*].accounts[*].id
```

Calls pricing with:

```json
{
  "ids": ["acc-a", "acc-b"],
  "currency": "USD"
}
```

Indexes pricing response entries by:

```text
$.data[*].id
```

Appends matched response entries to the owning `data[*]` item under:

```text
account1
```

### Owners

Reads owner ids from the main response with fallback fields:

```text
$.data[*].basicDetails.owners[*].id
$.data[*].basicDetails.owners[*].number
```

Calls owners with:

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

- `mainItems` selects the owner item level in the main response.
- main key paths are relative to each selected owner item.
- response key paths are relative to each selected response item.
- key paths are tried in order, so later paths are fallback values.
- request ids are deduplicated while merge still runs for every matching owner item.
- matched response entries are appended as whole JSON elements.

Supported path syntax is intentionally small:

```text
$
$.field
$.field[*]
$.field[*].nested.field
```

This is JSONPath-like syntax, not a full JSONPath engine. Filters, slices, indexes, bracket notation, and recursive descent are not supported.

## Renovate

Renovate is configured in [renovate.json](renovate.json):

- best-practices preset
- dependency dashboard
- dependency PR label
- Spring Boot plugin and BOM grouped together
- Lombok updates grouped together

Enable the Renovate GitHub App for the repository to start receiving dependency update PRs.

## SonarQube Cloud

SonarQube Cloud is wired through Gradle and GitHub Actions.

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

GitHub Actions runs tests on every pull request and push to `main`. The Sonar step runs when the repository has this secret:

```text
SONAR_TOKEN
```

Create the token in SonarQube Cloud, then add it in GitHub:

```text
Repository -> Settings -> Secrets and variables -> Actions -> New repository secret
```
