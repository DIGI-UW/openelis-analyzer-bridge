# BR-E0 Contract Evidence

Date: 2026-08-13

Base: Bridge `develop` at `53b6acbf2a3fedef0ddd9f582cb9cbdf86a59dd0`

## TDD record

1. Red: `AnalyzerContractArtifactsTest` failed six cases because no versioned
   schemas or canonical fixtures existed.
2. Green: added JSON Schema 2020-12 profile, registration, reconciliation,
   legacy compatibility, and normalized FHIR contracts plus known, unknown
   test, unknown value, QC, and FILE fixtures.
3. Refactor red: strict HAPI parsing rejected invalid root-level FHIR Bundle
   extensions.
4. Refactor green: moved contract/profile/protocol provenance to Device and
   transport/raw-value context to Observation; strict R4 parsing is clean.
5. Boundary hardening: negative tests reject OpenELIS local IDs in a portable
   profile and reject normalized traffic that loses raw analyzer code/value.
6. Compatibility: the canonical unversioned registration fixture deserializes
   through the current controller and a repeated full-state sync reports zero
   additions, updates, or removals.

## Validation

| Gate              | Result                                                 |
| ----------------- | ------------------------------------------------------ |
| Focused contracts | 11 passed                                              |
| `mvn test`        | 610 passed, 3 skipped, 0 failures/errors               |
| `mvn verify`      | 610 passed, 3 skipped, 0 failures/errors; packaged JAR |
| JSON syntax       | every contract and fixture passes `jq empty`           |
| Formatting        | Prettier applied to Java, Markdown, JSON, and XML      |

The three skipped tests require virtual serial hardware. Surefire logs its
existing 30-second fork shutdown warning after the application tests; both
Maven commands exit successfully.

## Scope boundary

This checkpoint publishes executable contracts and compatibility evidence. It
does not implement Bridge profile lifecycle (BR-M1), versioned runtime
registration (BR-M1), mapping/QC/capability evidence (BR-M2), or normalized
traffic runtime conformance (BR-M4).
