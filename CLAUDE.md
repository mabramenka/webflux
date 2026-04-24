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
2. POST ids to the `account-group` downstream. The main call is strict: unreadable/error/empty responses are mapped to `DownstreamClientException` and abort the whole request (→ 502/504 problem+json).
3. Expand dependencies and build dependency levels for the selected aggregation parts.
4. `AggregationPartExecutor` evaluates `supports(context)` against the current root snapshot for each level, then runs supported parts in that level in parallel. Errors propagate — any failure from a part aborts the whole request. Soft signals are carried as `AggregationPartResult.NoOp` (status `EMPTY` / `SKIPPED`): missing main keys, unsupported context, empty downstream bodies, and `404` from an enricher all become `NoOp`, not errors.
5. Applied part results are written to the mutable root in stable graph order before the next dependency level starts. `NoOp` results do not mutate root; dependent parts whose dependencies did not apply are marked `SKIPPED / DEPENDENCY_EMPTY` and their outcomes recorded.
6. The success response carries a `meta.parts.<name>` object for every selected or transitively-required part with `{ status, reason? }` — `APPLIED` (no reason), `EMPTY` (`DOWNSTREAM_EMPTY` / `DOWNSTREAM_NOT_FOUND`), or `SKIPPED` (`NO_KEYS_IN_MAIN` / `UNSUPPORTED_CONTEXT` / `DEPENDENCY_EMPTY`).

Failure boundaries:

- **Fatal (aborts the request):** main-call failures, auth (`401`/`403`) from any downstream, `5xx` / timeouts / transport errors from enrichers, decoding failures (`ENRICH_INVALID_PAYLOAD`), merge exceptions (`ORCH_MERGE_FAILED`), internal invariant violations (empty `Mono`, wrong part name, missing result).
- **Soft (part-level NoOp, request succeeds):** main payload missing the keys a selected part needs (`NO_KEYS_IN_MAIN`), part `supports(context)` returns `false` (`UNSUPPORTED_CONTEXT`), downstream body empty (`DOWNSTREAM_EMPTY`), downstream `404` on enricher (`DOWNSTREAM_NOT_FOUND`), dependency applied nothing (`DEPENDENCY_EMPTY`).

Key collaborators:

- `ClientRequestContext` (a per-invocation POJO, not a Spring scope bean — built by `ClientRequestContextFactory` + `ServerClientRequestContextArgumentResolver`) carries forwarded headers (`Authorization`, `X-Request-Id`, `X-Correlation-Id`, `Accept-Language`) and the `detokenize` query flag. On the downstream side, `ClientRequestContextHttpServiceArgumentResolver` (registered via `WebClientHttpServiceGroupConfigurer`) turns it into `WebClient` headers/query params for HTTP exchange methods.
- `dev.abramenka.aggregation.part` owns the optional-part engine: selection planning, dependency graph levels, per-part execution, metrics, and root result application.
- `AggregationPartPlanner` receives one ordered `List<AggregationPart>` from Spring. Do not reintroduce separate type-specific planning paths inside the engine.
- Cross-package engine entry points are `AggregationPartPlanner` and `AggregationPartExecutor`; `AggregationPartPlan` is a model record. Keep runner, metrics, merger, result applicator, graph, and execution state package-private.
- `DownstreamClientErrorFilter` (per-group WebClient filter) maps 4xx/5xx to `DownstreamClientException` with the human client name from `HttpServiceGroups.downstreamClientName`.
- `AggregationErrorResponseAdvice` maps validation, unreadable request content, downstream `ErrorResponseException` subclasses, and unexpected failures to RFC 9457-style `ProblemDetail` responses.

Aggregation parts:

- `AggregationPart` is the common SPI for optional pipeline behavior: `name()`, `dependencies()`, `supports(context)`, `execute(rootSnapshot, context)`.
- `AggregationPartResult` is a sealed `ReplaceDocument | MergePatch | NoOp`. Parts return a replacement, a merge patch derived from the snapshot, or a `NoOp` carrying a `PartOutcomeStatus` (`EMPTY` / `SKIPPED`) plus a `PartSkipReason`. `AggregationPartResult.empty(...)` / `.skipped(...)` / `.patch(...)` / `.replacement(...)` are the factories.
- `model.AggregationEnrichment` adds `fetch(context)` and `merge(root, response)`; its default `execute` merges successful responses into a snapshot, translates empty `Mono` into `empty(DOWNSTREAM_EMPTY)`, and translates `DownstreamClientException` with status `404` into `empty(DOWNSTREAM_NOT_FOUND)`.
- New business enrichments live under `enrichment.<name>`; shared helper mechanics live under `enrichment.support`.
- `KeyedArrayEnrichment` (base class for `account.AccountEnrichment`, `owners.OwnersEnrichment`) is configured declaratively via `EnrichmentRule` (main-item path, main key paths with fallbacks, response-item path, response key paths with fallbacks, `requestKeysField`, `targetField`). It short-circuits to `skipped(NO_KEYS_IN_MAIN)` when the main payload yields no keys, and its `merge` tolerates downstream responses that omit some requested keys (only matching entries are attached — no error).
- `enrichment.account.AccountEnrichment` and `enrichment.owners.OwnersEnrichment` are optional fetches: they use `DownstreamClientResponses.optionalBody` so an empty body flows into the enrichment's `switchIfEmpty` rather than being mapped to `MAIN_CONTRACT_VIOLATION`. The main `account-group` call still uses `requireBody` and is strict.
- `beneficialowners.BeneficialOwnersEnrichment` overrides `execute` to short-circuit to `skipped(NO_KEYS_IN_MAIN)` when no root `owners1` entities are present; its nested batch calls inside `OwnershipResolver` remain strict (`requireBody`) because a partial tree is a contract violation, not a soft skip.
- `PathExpression` is an intentionally tiny JSONPath-like engine under `enrichment.support.keyed`: only `$`, `$.field`, `$.field[*]`, `$.field[*].nested`. No filters/slices/indexes/brackets/recursive descent — do not extend it casually; keep paths within this grammar.

Metrics (Micrometer): `aggregation.request` tagged by `part_selection` (`all`/`subset`) and `requested_parts` (count); `aggregation.part.requests` tagged by `part` and `outcome` (`success` for `APPLIED`, `empty` for `EMPTY`, `skipped` for `SKIPPED`, `failure` for exceptions).

Config profiles: `application.yaml` is the default; `application-structured.yaml` switches logging to ECS JSON (activate with `--spring.profiles.active=structured`). Actuator exposure is locked down to `health`, `info`, `metrics` (plus liveness/readiness probes).

## Conventions

- Prefer editing downstream payloads as Jackson `JsonNode` / `ObjectNode`; do not introduce fixed response DTOs for enrichment bodies.
- Spotless (`palantirJavaFormat`, unused-import removal, import ordering) is enforced; run `spotlessApply` before committing when you've touched Java.
- Versions live in `gradle/libs.versions.toml`. Prefer the catalog over inline coordinates; Renovate groups Spring Boot plugin/BOM and Lombok updates.
