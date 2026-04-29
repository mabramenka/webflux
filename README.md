# Aggregation Gateway

[![Java 21](https://img.shields.io/badge/Java-21-007396?logo=openjdk&logoColor=white)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot 4.0.6](https://img.shields.io/badge/Spring%20Boot-4.0.6-6DB33F?logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![Spring WebFlux](https://img.shields.io/badge/WebFlux-reactive-6DB33F?logo=spring&logoColor=white)](https://docs.spring.io/spring-framework/reference/web/webflux.html)
[![Gradle 9.5.0](https://img.shields.io/badge/Gradle-9.5.0-02303A?logo=gradle&logoColor=white)](https://gradle.org/)
[![Jackson 3.1.2](https://img.shields.io/badge/Jackson-3.1.2-2F6DB3)](https://github.com/FasterXML/jackson)
[![Renovate](https://img.shields.io/badge/Renovate-enabled-1A1F6C?logo=renovatebot&logoColor=white)](https://docs.renovatebot.com/)
[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=mabramenka_webflux&metric=alert_status&token=f775095566196b3449bd25c7a899aaf4204526ae)](https://sonarcloud.io/summary/new_code?id=mabramenka_webflux)
[![Coverage](https://sonarcloud.io/api/project_badges/measure?project=mabramenka_webflux&metric=coverage&token=f775095566196b3449bd25c7a899aaf4204526ae)](https://sonarcloud.io/summary/new_code?id=mabramenka_webflux)
[![Bugs](https://sonarcloud.io/api/project_badges/measure?project=mabramenka_webflux&metric=bugs&token=f775095566196b3449bd25c7a899aaf4204526ae)](https://sonarcloud.io/summary/new_code?id=mabramenka_webflux)
[![Code Smells](https://sonarcloud.io/api/project_badges/measure?project=mabramenka_webflux&metric=code_smells&token=f775095566196b3449bd25c7a899aaf4204526ae)](https://sonarcloud.io/summary/new_code?id=mabramenka_webflux)
[![Duplicated Lines (%)](https://sonarcloud.io/api/project_badges/measure?project=mabramenka_webflux&metric=duplicated_lines_density&token=f775095566196b3449bd25c7a899aaf4204526ae)](https://sonarcloud.io/summary/new_code?id=mabramenka_webflux)

Reactive Spring Boot aggregation facade. It validates account-group aggregation requests, runs the mandatory internal `accountGroup` base part, executes selected public enrichment parts by dependency level, and returns one merged JSON document.

The service intentionally keeps downstream payloads dynamic by working with Jackson `JsonNode` / `ObjectNode` instead of fixed response DTOs.

## Highlights

- Spring Boot 4 / Spring Framework 7 WebFlux service
- Spring HTTP service clients backed by reactive `WebClient`
- Mandatory non-public `accountGroup` base part
- Public enrichments: `account`, `owners`, `beneficialOwners`
- Dependency-expanded, level-based part execution with same-level concurrency
- Workflow-based keyed joins and bounded recursive beneficial-owner traversal
- Header and query parameter forwarding to downstream calls
- RFC 9457-style facade-owned problem responses
- `meta.parts` outcomes: `APPLIED`, `EMPTY`, `SKIPPED`, `FAILED`
- Actuator health, readiness, liveness, info, and metrics endpoints
- NullAway, Error Prone, Spotless, JaCoCo, OWASP Dependency Check, SonarQube Cloud, Renovate

## Documentation

- [Architecture](docs/architecture.md)
- [Error handling design](docs/error-handling-design.md)
- [Workflow enrichment guide](docs/workflow-enrichment-guide.md)
- [Architecture review notes](docs/architecture-review.md)
- [Changelog](CHANGELOG.md)

## Stack

The build uses stable release dependencies. Release candidates such as Spring Boot `4.1.0-RC1` are not part of the default baseline.

- Java 21 toolchain
- Gradle 9.5.0 wrapper with dependency verification
- Spring Boot 4.0.6
- Spring Framework 7.0.7
- Jackson 3.1.2
- Reactor 3.8.5
- Micrometer Context Propagation 1.2.1
- springdoc-openapi 3.0.3
- Lombok, Error Prone 2.49.0, NullAway 0.13.4
- Spotless 8.4.0, JaCoCo 0.8.14, OWASP Dependency-Check 12.2.1
- SonarQube Gradle plugin 7.2.3.7755

Version pins live in [libs.versions.toml](gradle/libs.versions.toml). BOM-managed runtime versions can be checked with `dependencyInsight`.

```bash
./gradlew printStackVersions
./gradlew dependencyInsight --dependency org.springframework:spring-core --configuration runtimeClasspath
./gradlew dependencyInsight --dependency tools.jackson.core:jackson-databind --configuration runtimeClasspath
```

## Run

HTTPS is the default runtime profile. The service listens on `:8443` and uses Spring Boot SSL bundles for inbound TLS and outbound downstream trust.

Generate local development certificates before the first `bootRun`:

```bash
./scripts/gen-dev-certs.sh
./gradlew bootRun
```

Generated PEM files are written under `src/main/resources/certs/dev/` and are gitignored. CI and production must supply their own key material through mounted secrets and environment-specific Spring configuration.

Default runtime configuration is in [application.yaml](src/main/resources/application.yaml).

| Client group | Default base URL in `https` profile | Read timeout |
| --- | --- | --- |
| `account-group` | `https://localhost:8081` | `2s` |
| `account` | `https://localhost:8083` | `3s` |
| `owners` | `https://localhost:8084` | `3s` |

## API

The controller binds `/api/{apiVersion}/aggregate` to API version `1`; `v1` is accepted and unsupported versions are rejected by Spring versioned routing.

OpenAPI 3 and Swagger UI:

- `GET /api/v1/v3/api-docs`
- `GET /api/v1/swagger-ui.html`

Aggregate multiple ids:

```http
POST /api/v1/aggregate
Content-Type: application/json
Accept: application/json
```

```json
{
  "ids": ["id-x19"],
  "include": ["account", "owners"]
}
```

Aggregate one id:

```http
GET /api/v1/aggregate/{id}?include=account&include=owners
Accept: application/json
```

### Request Fields

| Field | Required | Notes |
| --- | --- | --- |
| `ids` | Yes | Non-empty array of non-blank, pattern-matching ids. Values are upper-cased before the account-group downstream request. |
| `include` | No | Nullable list of public enrichment part names. Max 32 values. |

Supported public `include` values:

- `account`
- `owners`
- `beneficialOwners`

`accountGroup` is mandatory, internal, and not public-selectable. Asking for `include: ["accountGroup"]` fails as validation.

Include semantics:

| `include` value | Effective plan |
| --- | --- |
| omitted or `null` | `accountGroup` plus all public enrichments |
| `[]` | only `accountGroup` |
| `["account"]` | `accountGroup`, `account` |
| `["owners"]` | `accountGroup`, `owners` |
| `["beneficialOwners"]` | `accountGroup`, `owners`, `beneficialOwners` |

### Query Parameters

| Parameter | Applies to | Notes |
| --- | --- | --- |
| `detokenize=true|false` | all downstream calls | Optional boolean forwarded through the client request context. |
| `fields=a,b,c` | account-group downstream call | Optional projection override. Missing or blank uses `id,status,name,accounts,owners1`. Treat this as an advanced integration knob because projecting away fields needed by selected enrichments can produce soft part outcomes under the current contract. |

### Forwarded Headers

The service forwards these inbound headers to downstream services:

- `Authorization`
- `X-Request-Id`
- `X-Correlation-Id`
- `Accept-Language`

Swagger UI's "Authorize" dialog accepts an `Authorization` header value and forwards it verbatim; it does not add a `Bearer ` prefix.

## Responses

Successful responses are the account-group JSON object with selected enrichment data merged in. Public part outcomes are attached only when there is public part metadata:

```json
{
  "data": [],
  "meta": {
    "parts": {
      "owners": {
        "status": "APPLIED",
        "criticality": "REQUIRED"
      }
    }
  }
}
```

The current public enrichment fields are:

| Part | Writes |
| --- | --- |
| `account` | `account1` on matched `data[*]` items |
| `owners` | `owners1` on matched `data[*]` items |
| `beneficialOwners` | `beneficialOwnersDetails` under entity owners in `owners1` |

Request-level failures return `application/problem+json` using the catalog in [error-handling-design.md](docs/error-handling-design.md). Every problem response includes stable `type`, `errorCode`, `category`, `retryable`, `traceId`, `timestamp`, and `instance` fields.

Every success and error response carries a valid W3C `traceparent` header. If the request supplied a valid `traceparent`, it is echoed; otherwise the service generates one.

## Operations

Actuator endpoints exposed by default:

- `/actuator/health`
- `/actuator/health/liveness`
- `/actuator/health/readiness`
- `/actuator/info`
- `/actuator/metrics`

Custom metrics:

- `aggregation.request`, tagged by `part_selection` and `requested_parts`
- `aggregation.part.requests`, tagged by `part` and `outcome`
- `aggregation.beneficial_owners.tree`, tagged by `outcome`

## Quality Gates

Run tests:

```bash
./gradlew test
```

Run formatting checks, tests, Boot 4 classpath verification, and JaCoCo coverage verification:

```bash
./gradlew check
```

Run OWASP Dependency Check when an NVD API key is available:

```bash
export NVD_API_KEY=...
./gradlew securityCheck
```

Apply formatting:

```bash
./gradlew spotlessApply
```

Generate coverage reports:

```bash
./gradlew jacocoTestReport
```

Reports are written under `build/reports/`.

## Dependency Maintenance

Renovate is configured in [renovate.json](renovate.json):

- best-practices preset
- dependency dashboard
- dependency PR label
- grouped Spring Boot, Lombok, Jackson, Micrometer, and Reactor updates

The Gradle `check` task also runs `verifyBoot4Classpath`, which fails if Boot 3, Spring Framework 6, or disallowed Jackson 2 artifacts enter runtime classpaths.

## SonarQube Cloud

SonarQube Cloud is wired through Gradle:

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

The Sonar task requires `SONAR_TOKEN` in the environment.
