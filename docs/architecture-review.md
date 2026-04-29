# Architecture Review Notes

**Status:** current review synthesis for the local repository.
**Last refreshed:** 2026-04-30.

This document keeps the useful material from the removed one-off prompt/review files:

- `account_group_base_enrichment_prompt_20260428_201354.md`
- `codex_review_20260428_193050.md`
- `gpt_review_20260428_193136.md`

Those files were useful during implementation and review, but they were not stable project documentation. Their durable points are now split between this file, [architecture.md](architecture.md), [error-handling-design.md](error-handling-design.md), and [workflow-enrichment-guide.md](workflow-enrichment-guide.md).

## What Was Kept Where

| Source material | Durable content | Current home |
| --- | --- | --- |
| Account-group base-enrichment prompt | `accountGroup` is a mandatory base part, not a hardcoded `AggregateService` pre-step. It is non-public, dependency-free, always included, and maps failures to main dependency problems. | [architecture.md](architecture.md) and [README.md](../README.md) |
| Account-group base-enrichment prompt | Planner semantics for `include == null`, `include == []`, transitive dependencies, and public-name validation. | [architecture.md](architecture.md) and [README.md](../README.md) |
| Account-group base-enrichment prompt | Acceptance criteria that `AggregateService` starts from an empty root and no longer owns `AccountGroups`. | [architecture.md](architecture.md) |
| Codex/GPT reviews | README version drift, especially Gradle `9.4.1` vs wrapper `9.5.0`. | Fixed in [README.md](../README.md) |
| Codex/GPT reviews | Invalid-body error contract disagreement. Runtime and docs now identify malformed JSON as `CLIENT-INVALID-BODY`. | [error-handling-design.md](error-handling-design.md) |
| Codex/GPT reviews | Open design risks: soft required-enrichment outcomes, public `fields`, owners fallback matching, OpenAPI gaps, retry policy, downstream trace propagation, dynamic response contract. | This document |

## Current Verdict

The architecture skeleton is usable and internally coherent:

- controller is thin;
- `AggregateService` is an orchestration shell;
- `accountGroup` is now an explicit base `AggregationPart`;
- selected public parts are planned through one graph;
- same-level public parts run concurrently;
- public failures are normalized through the facade problem catalog;
- build quality gates are strong for a small service.

The main remaining risk is contract semantics. The current implementation intentionally allows required public enrichments to produce `EMPTY` or `SKIPPED` success-side outcomes in `meta.parts`. That is coherent with the current code and error docs, but it may conflict with a product rule that selected/default enrichment data must be present or the request must fail.

## Resolved Or Superseded Findings

| Finding | Status |
| --- | --- |
| `AggregateService` directly calls `AccountGroups` before part execution. | Resolved. `AccountGroupEnrichment` is the internal base part and `AggregateService` starts from an empty root. |
| `accountGroup` could be treated as a normal public include. | Resolved. It is `publicSelectable() == false`; requesting it fails validation. |
| Base-part behavior could be implicit and order-sensitive. | Resolved. The part contract has explicit `base()` and `publicSelectable()` metadata. |
| README claims Gradle `9.4.1` while wrapper uses `9.5.0`. | Resolved in README. |
| Docs said malformed JSON maps to validation while code uses `CLIENT-INVALID-BODY`. | Resolved in error docs; distinct invalid-body code is the current contract. |
| One-off prompt/review files were linked from README as if they were stable docs. | Resolved. README now links only stable docs and this review synthesis. |

## Open Follow-Ups

### 1. Required vs soft enrichment semantics

Current behavior:

- missing keys in the main payload can produce `SKIPPED / NO_KEYS_IN_MAIN`;
- empty enrichment body can produce `EMPTY / DOWNSTREAM_EMPTY`;
- handled enrichment `404` can produce `EMPTY / DOWNSTREAM_NOT_FOUND`;
- this can happen for `REQUIRED` parts.

Decision needed:

- keep the current "required means fatal only for technical dependency failures" model; or
- change required selected/default enrichments so missing keys, empty downstream data, enrichment 404, partial key coverage, and schema mismatches fail with a request-level problem.

If the second option is chosen, update tests before code so the new product contract is explicit.

### 2. Public `fields` projection

`fields` is parsed from public query parameters and currently overrides the account-group downstream projection. This can remove fields that selected enrichments need and can make soft outcomes more likely.

Options:

- keep it public and document it as an advanced integration knob;
- whitelist or merge caller projections with fields required by selected enrichments;
- remove it from the public API and keep projection logic internal.

### 3. Owners fallback matching

`owners` extracts keys from both:

```text
basicDetails.owners[*].id
basicDetails.owners[*].number
```

It indexes responses by both:

```text
individual.number
id
```

But the current write rule matches:

```text
basicDetails.owners[*].id -> individual.number
```

Add a regression test for number-only owner references. If the fetched owner is not attached, extend the write rule matching model or make the owners part explicit Java logic for this case.

### 4. OpenAPI contract depth

Swagger UI is configured, but the generated OpenAPI contract likely under-documents:

- forwarded headers;
- `detokenize` and `fields`;
- `meta.parts`;
- reusable problem responses;
- dynamic success response extension points.

Add reusable OpenAPI components or a customizer once the response/error semantics are settled.

### 5. Retry policy

`DownstreamClientErrorFilter` retries transient transport/timeout-like failures once for every downstream client, including `POST` calls. HTTP 503/504 statuses are normalized after the exchange and are not retried by that filter.

Decide whether downstream POSTs are idempotent. If not, make retries opt-in per client/operation or propagate an idempotency key.

### 6. Downstream trace propagation

Problem responses generate or echo a W3C `traceparent` header. The explicitly forwarded-header model does not include `traceparent`.

Add an integration test proving downstream requests receive a valid trace context through Micrometer/WebClient instrumentation or explicit forwarding.

### 7. Dynamic public response shape

The facade returns the account-group JSON object with enrichment fields and `meta.parts` added. This keeps the service flexible but couples API consumers to downstream-shaped JSON.

If consumers need a long-lived public contract, define either:

- a stable response envelope; or
- a JSON Schema/OpenAPI schema that documents required fields and extension points.

## Dependency Baseline Check

Checked against Gradle service metadata, Maven Central metadata, and Gradle Plugin Portal metadata on 2026-04-30:

Metadata roots:

- https://services.gradle.org/versions/current
- https://repo.maven.apache.org/maven2/
- https://plugins.gradle.org/m2/

| Component | Repository baseline | Metadata result |
| --- | --- | --- |
| Gradle | `9.5.0` | current release |
| Spring Boot | `4.0.6` | latest stable 4.0.x; `4.1.0-RC1` exists but is pre-release |
| Spring Framework | `7.0.7` via Boot BOM | current stable 7.0.x |
| Jackson | `3.1.2` via Boot BOM | current release |
| Reactor | `3.8.5` via Boot BOM | current release |
| Micrometer Context Propagation | `1.2.1` via Boot BOM | current release |
| JaCoCo | `0.8.14` | current release |
| Error Prone | `2.49.0` | current release |
| NullAway | `0.13.4` | current release |
| Spotless plugin | `8.4.0` | current release |
| OWASP Dependency-Check plugin | `12.2.1` | current release |
| SonarQube Gradle plugin | `7.2.3.7755` | current release |
| FreeFair Lombok plugin | `9.5.0` | current release |
| springdoc-openapi | `3.0.3` | current release |

No dependency catalog change was needed for stable releases. The only README drift was the Gradle badge/stack value.

## Verification Commands

Useful commands for future review refreshes:

```bash
./gradlew printStackVersions
./gradlew --version
./gradlew dependencyInsight --dependency org.springframework:spring-core --configuration runtimeClasspath
./gradlew dependencyInsight --dependency tools.jackson.core:jackson-databind --configuration runtimeClasspath
./gradlew test
./gradlew check
```
