# Aggregation API design notes

## Suggested package structure

- `com.example.aggregation.controller`: thin HTTP layer only.
- `com.example.aggregation.service`: orchestration, optional/mandatory policy, and merge flow.
- `com.example.aggregation.client`: downstream client contracts.
- `com.example.aggregation.client.impl`: WebClient client implementations and HTTP status mapping.
- `com.example.aggregation.config`: infrastructure beans such as named WebClient instances.
- `com.example.aggregation.web`: inbound-to-downstream transport value objects (header propagation).
- `com.example.aggregation.json` (future): reusable JSON merge helpers once multiple aggregators share merge rules.

## Why this design works for WebFlux

- The controller remains thin and delegates orchestration.
- The service makes the mandatory main call first, then runs optional downstream calls in parallel with `Mono.zip`.
- Optional branches always return exactly one `JsonNode` (`MissingNode` when skipped/failed), so zip composition remains deterministic.
- Final merge starts from a deep copy of the main JSON and only writes optional sections when values are present.
