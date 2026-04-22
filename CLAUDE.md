# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Verify

- `./gradlew bootRun` — start the service.
- `./gradlew test` — unit + integration tests; `jacocoTestReport` runs as a finalizer.
- `./gradlew check` — spotless, tests, JaCoCo coverage gate (line ≥ 0.80), and `verifyBoot4Classpath`. This is the authoritative local gate; CI runs the same.
- `./gradlew test --tests "dev.abramenka.aggregation.service.AggregateServiceTest"` — run a single test class (append `.methodName` for a single method).
- `./gradlew spotlessApply` — apply Palantir Java formatting.
- `./gradlew verifyBoot4Classpath` — fails if Boot 3 / Spring Framework 6 / Jackson 2 leak onto `runtimeClasspath` or `testRuntimeClasspath`.
- `./gradlew securityCheck` — OWASP Dependency-Check; slow without `NVD_API_KEY`, deliberately not wired into `check`.
- `./gradlew sonar` — SonarQube Cloud analysis; needs `SONAR_TOKEN` and a prior `test jacocoTestReport`.
- `./gradlew printStackVersions` — dumps the version catalog values used in the README badges.
- `javac` is invoked with `-Werror -Xlint:deprecation -Xlint:removal`; deprecation/removal warnings break the build.

## Stack Constraints

- Java 21, Spring Boot 4.0.5, Spring Framework 7, Jackson 3.1.x (`tools.jackson.*` — not `com.fasterxml.jackson.*`). The Jackson BOM is applied on top of the Boot BOM to stay on 3.1.x.
- NullAway runs in error mode on `dev.abramenka.aggregation` in JSpecify mode. New code should use JSpecify `@Nullable` / `@NonNull` and avoid triggering NullAway.
- HTTP clients are Spring HTTP service interfaces (`@HttpExchange`) backed by reactive `WebClient`, registered via `@ImportHttpServices` in `HttpServiceClientConfig`. Three groups: `account-group` (`AccountGroups` → `POST /account-groups`, default `:8081`), `account` (`Accounts` → `POST /accounts`, `:8083`), `owners` (`Owners` → `POST /owners`, `:8084`). Base URLs and timeouts live under `spring.http.serviceclient.*` in `application.yaml` (the only config source — README's `.properties` snippet is illustrative).

## Architecture

Entry: `POST /api/v1/aggregate` → `AggregateController` (`@Valid` on `AggregateRequest`) → `AggregateService`.

Aggregation pipeline in `AggregateService.aggregate`:

1. Build `AggregationPartSelection` from `request.include()` and validate unknown names up front.
2. POST ids to the `account-group` downstream. Unreadable/error responses are mapped to `DownstreamClientException` (→ 502 problem+json via Spring's ProblemDetails).
3. Expand dependencies and filter registered aggregation parts by selection and by `supports(context)`.
4. `EnrichmentExecutor` fetches supported enrichments in dependency order with per-part failure isolation (failures are swallowed, metric tagged `outcome=failure`).
5. `AggregationMerger` mutates a copy of the account-group response, then supported post-processors run in dependency order.

Key collaborators:

- `ClientRequestContext` (a per-invocation POJO, not a Spring scope bean — built by `ClientRequestContextFactory` + `ServerClientRequestContextArgumentResolver`) carries forwarded headers (`Authorization`, `X-Request-Id`, `X-Correlation-Id`, `Accept-Language`) and the `detokenize` query flag. On the downstream side, `ClientRequestContextHttpServiceArgumentResolver` (registered via `WebClientHttpServiceGroupConfigurer`) turns it into `WebClient` headers/query params for HTTP exchange methods.
- `DownstreamClientErrorFilter` (per-group WebClient filter) maps 4xx/5xx to `DownstreamClientException` with the human client name from `HttpServiceGroups.downstreamClientName`.
- `AggregationErrorResponseAdvice` maps validation, unreadable request content, downstream `ErrorResponseException` subclasses, and unexpected failures to RFC 9457-style `ProblemDetail` responses.

Aggregation parts:

- `AggregationPart` is the common SPI for optional pipeline behavior: `name()`, `dependencies()`, `supports(context)`.
- `AggregationEnrichment` adds `fetch(context)` and `merge(root, response)`.
- `AggregationPostProcessor` adds `apply(root, context)`.
- `KeyedArrayEnrichment` (base class for `AccountEnrichment`, `OwnersEnrichment`) is configured declaratively via `EnrichmentRule` (main-item path, main key paths with fallbacks, response-item path, response key paths with fallbacks, `requestKeysField`, `targetField`).
- `PathExpression` is an intentionally tiny JSONPath-like engine: only `$`, `$.field`, `$.field[*]`, `$.field[*].nested`. No filters/slices/indexes/brackets/recursive descent — do not extend it casually; keep paths within this grammar.

Metrics (Micrometer): `aggregation.request` tagged by `part_selection` (`all`/`subset`) and `requested_parts` (count); `aggregation.part.requests` tagged by `part` and `outcome`.

Config profiles: `application.yaml` is the default; `application-structured.yaml` switches logging to ECS JSON (activate with `--spring.profiles.active=structured`). Actuator exposure is locked down to `health`, `info`, `metrics` (plus liveness/readiness probes).

## Conventions

- Prefer editing downstream payloads as Jackson `JsonNode` / `ObjectNode`; do not introduce fixed response DTOs for enrichment bodies.
- Spotless (`palantirJavaFormat`, unused-import removal, import ordering) is enforced; run `spotlessApply` before committing when you've touched Java.
- Versions live in `gradle/libs.versions.toml`. Prefer the catalog over inline coordinates; Renovate groups Spring Boot plugin/BOM and Lombok updates.
