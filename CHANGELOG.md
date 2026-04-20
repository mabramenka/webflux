# Changelog

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
