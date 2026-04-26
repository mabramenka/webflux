# Changelog

## [0.12.0](https://github.com/mabramenka/webflux/compare/v0.11.0...v0.12.0) (2026-04-26)


### Features

* **workflow:** add compute step (Phase 12) ([312d8e5](https://github.com/mabramenka/webflux/commit/312d8e5b70f6929934a0ecefaa369b2a4a493b62))
* **workflow:** harden patch conflict detection and write ownership (Phase 13) ([479a152](https://github.com/mabramenka/webflux/commit/479a1524317eb8a889f2027063aa5cc82f4ceea3))
* **workflow:** migrate owners enrichment to workflow model (Phase 10) ([2f01c6f](https://github.com/mabramenka/webflux/commit/2f01c6f8093c18a0454789fd48150cbc1b7cf21f))
* **workflow:** multi-binding workflow with CURRENT_ROOT and STEP_RESULT (Phase 11) ([fbc5929](https://github.com/mabramenka/webflux/commit/fbc5929627856c55265f4af155a8288666cf3f4c))

## [0.11.0](https://github.com/mabramenka/webflux/compare/v0.10.1...v0.11.0) (2026-04-26)


### Features

* **workflow:** add binding-level metrics (Phase 9) ([d37cd45](https://github.com/mabramenka/webflux/commit/d37cd45965c74b645a48ecef791472fd7f47740f))
* **workflow:** add keyed binding step (Phase 7) ([96fc87b](https://github.com/mabramenka/webflux/commit/96fc87b5e1b488667bd62b96d30a1bcc2f5b2173))
* **workflow:** migrate account enrichment to workflow model (Phase 8) ([12a4788](https://github.com/mabramenka/webflux/commit/12a478822c2af7fa565909fbbed53018de8cab15))

## [0.10.1](https://github.com/mabramenka/webflux/compare/v0.10.0...v0.10.1) (2026-04-25)


### Bug Fixes

* dependencyCheck ([4619c7f](https://github.com/mabramenka/webflux/commit/4619c7faf04574195969afdeece4bd75ecfb69b0))

## [Unreleased]

### Refactoring

* split problem-response support and validation-error mapping out of the global error advice
* split beneficial-owners enrichment into focused helper collaborators
* split large aggregate service and controller test suites into scenario-focused classes

### Documentation

* align README and error-handling design docs with current soft-outcome aggregation semantics

## [0.10.0](https://github.com/mabramenka/webflux/compare/v0.9.0...v0.10.0) (2026-04-24)


### Features

* **observability:** emit a per-part Observation in AggregationPartRunner ([d423493](https://github.com/mabramenka/webflux/commit/d423493f949d89ec77e286141abd345cc53369f6))

## [0.9.0](https://github.com/mabramenka/webflux/compare/v0.8.0...v0.9.0) (2026-04-24)


### Features

* forward `fields` projection to main account-group call only ([7b9128c](https://github.com/mabramenka/webflux/commit/7b9128cc62442434802babcafcbd5a3ccf789e1b))

## [0.8.0](https://github.com/mabramenka/webflux/compare/v0.7.4...v0.8.0) (2026-04-24)


### Features

* soft-skip semantics for optional enrichments with meta.parts outcomes ([ed36721](https://github.com/mabramenka/webflux/commit/ed36721e97bdbced3ad533c6e67f6ee9092da6ae))

## [0.7.4](https://github.com/mabramenka/webflux/compare/v0.7.3...v0.7.4) (2026-04-23)


### Bug Fixes

* delete hardcoded lombok, jackson versions ([bcb9c57](https://github.com/mabramenka/webflux/commit/bcb9c57436fcaffc532e079da2c4238a5fb1bd1a))

## [0.7.3](https://github.com/mabramenka/webflux/compare/v0.7.2...v0.7.3) (2026-04-23)


### Bug Fixes

* Add dedicated problem catalog entry for invalid request bodies ([3647622](https://github.com/mabramenka/webflux/commit/3647622d37aed7d60781b7bb6e089b38761b11cd))

## [0.7.2](https://github.com/mabramenka/webflux/compare/v0.7.1...v0.7.2) (2026-04-22)


### Bug Fixes

* **deps:** update dependency org.projectlombok:lombok to v1.18.46 ([a91297f](https://github.com/mabramenka/webflux/commit/a91297f13ac2da721b41974a8ecfd1f95d01c4bd))

## [0.7.1](https://github.com/mabramenka/webflux/compare/v0.7.0...v0.7.1) (2026-04-21)


### Bug Fixes

* **deps:** update dependency com.uber.nullaway:nullaway to v0.13.4 ([dd3927c](https://github.com/mabramenka/webflux/commit/dd3927c66bf4a8e4c9ea744bf7a5a4ec29400a2d))

## [0.7.0](https://github.com/mabramenka/webflux/compare/v0.6.1...v0.7.0) (2026-04-21)


### Features

* **postprocessor:** add beneficialOwners recursive resolution phase ([2d6777d](https://github.com/mabramenka/webflux/commit/2d6777de68514633b2f6a8aaf08d64db9068b002))

## [0.6.1](https://github.com/mabramenka/webflux/compare/v0.6.0...v0.6.1) (2026-04-20)


### Bug Fixes

* **api:** reject null aggregate ids ([6166d93](https://github.com/mabramenka/webflux/commit/6166d932fdc69b9da6ae17c2b2f45ffd97f09697))

## [0.6.0](https://github.com/mabramenka/webflux/compare/v0.5.0...v0.6.0) (2026-04-20)


### Features

* **api:** declare API version via Spring 7 ApiVersionStrategy ([0ac997b](https://github.com/mabramenka/webflux/commit/0ac997b6a0310961e09b486b8a1a5a5c069cb97c))

## [0.5.0](https://github.com/mabramenka/webflux/compare/v0.4.0...v0.5.0) (2026-04-20)


### Features

* **api:** add GET /aggregate/{id} and enforce 2-letter + 9-digit id pattern ([b85d519](https://github.com/mabramenka/webflux/commit/b85d51952287440de32c5314bd903cf0c61d8c9a))

## [0.4.0](https://github.com/mabramenka/webflux/compare/v0.3.0...v0.4.0) (2026-04-20)


### Features

* **ssl:** enable TLS inbound and outbound via Spring SSL bundles ([fd9bcb0](https://github.com/mabramenka/webflux/commit/fd9bcb03ca8bc72dcf86e313ae7c5747c8e40e4a))

## [0.3.0](https://github.com/mabramenka/webflux/compare/v0.2.1...v0.3.0) (2026-04-20)


### Features

* **observability:** propagate request and correlation ids to logs via MDC ([07e0f4b](https://github.com/mabramenka/webflux/commit/07e0f4b2b79a2aff33091659cbc781e5db33c0c2))


### Bug Fixes

* harden downstream client and request validation ([3f762ae](https://github.com/mabramenka/webflux/commit/3f762ae4d6f9e819ee491af31be7109767d7d1c7))


### Performance Improvements

* memoize per-request enrichment target resolution ([0724f51](https://github.com/mabramenka/webflux/commit/0724f51df9e9d36e1442ea1a68ccb265512f5cc3))

## [0.2.1](https://github.com/mabramenka/webflux/compare/v0.2.0...v0.2.1) (2026-04-19)


### Bug Fixes

* **deps:** update dependency com.google.errorprone:error_prone_core to v2.49.0 ([e3fb929](https://github.com/mabramenka/webflux/commit/e3fb929d79089a0fdb5bc669c93969b1925a72e9))
* **deps:** update dependency com.uber.nullaway:nullaway to v0.13.3 ([33ec765](https://github.com/mabramenka/webflux/commit/33ec765c396dbbaa0e75f9f6082729234517e4aa))

## [0.2.0](https://github.com/mabramenka/webflux/compare/v0.1.0...v0.2.0) (2026-04-19)


### Features

* add coverage gate, structured logging profile, stack-versions task ([7db5f95](https://github.com/mabramenka/webflux/commit/7db5f95cb684a1f31e30c820d993c871a345afd2))

## [0.1.0](https://github.com/mabramenka/webflux/compare/v0.0.3...v0.1.0) (2026-04-19)


### Features

* validate aggregate request with Jakarta @Valid at API boundary ([8448d07](https://github.com/mabramenka/webflux/commit/8448d074bca692640698e66a40c512992d6dfa9d))

## [0.0.3](https://github.com/mabramenka/webflux/compare/v0.0.2...v0.0.3) (2026-04-19)


### Bug Fixes

* trigger release-please ([5db5cad](https://github.com/mabramenka/webflux/commit/5db5cad07e86e628e327a8bac6636030ae51c6b6))

## [0.0.2](https://github.com/mabramenka/webflux/compare/v0.0.1...v0.0.2) (2026-04-17)


### Bug Fixes

* add SLF4J provider for Sonar scanner ([56c598d](https://github.com/mabramenka/webflux/commit/56c598defa3435765c8c192bf667cbc7a032524b))
* add SLF4J provider for Sonar scanner ([7cc76d5](https://github.com/mabramenka/webflux/commit/7cc76d5444210fbe157aa9a299de7fb428fec344))
