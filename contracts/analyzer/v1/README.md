# Analyzer Contract v1

This directory is the executable BR-E0 contract boundary for OGC-1054. BR-M1
extends it compatibly with the profile catalog entry envelope used by the
Bridge profile lifecycle API. Safe-traffic runtime changes remain BR-M4 work.

## Authority boundary

- Bridge owns portable profile identity, revision, protocol behavior, parsing,
  normalized analyzer concepts, QC identification, and runtime transport.
- OpenELIS owns analyzer instances, laboratory units, local Test and Result
  Option bindings, mapping verification and audit, operational QC, readiness,
  held results, and downstream clinical processing.
- Portable profiles contain no OpenELIS database identifiers.
- Registration sends desired analyzer-instance configuration and operational QC
  evidence/context. It does not send Westgard definitions for Bridge to
  evaluate and does not transfer local catalog ownership to Bridge.
- Normalized FHIR preserves raw analyzer code and value even when a portable
  normalized coding is known.

## Versioned artifacts

| Artifact                               | Direction          | Runtime owner |
| -------------------------------------- | ------------------ | ------------- |
| `portable-profile.schema.json`         | Bridge -> OpenELIS | BR-M1         |
| `profile-catalog-entry.schema.json`    | Bridge -> OpenELIS | BR-M1         |
| `registration-sync.schema.json`        | OpenELIS -> Bridge | BR-M1         |
| `registration-sync-result.schema.json` | Bridge -> OpenELIS | BR-M1         |
| `normalized-fhir-bundle.schema.json`   | Bridge -> OpenELIS | BR-M4         |

The files under `fixtures/` are canonical producer/consumer inputs. Bridge and
OpenELIS must run the same fixtures. A later milestone may add fields
compatibly, but changing required meaning or removing a field requires a new
major contract directory.

The `profile` member of each catalog entry independently conforms to
`portable-profile.schema.json`. The envelope adds immutable fingerprint and
latest lifecycle audit evidence without transferring local OpenELIS bindings
into the portable profile.

## Registration transport

OpenELIS sends v1 desired state to `PUT /api/analyzers/sync` with:

- `Content-Type: application/vnd.openelis.analyzer-registration.v1+json`
- `Accept: application/vnd.openelis.analyzer-registration-result.v1+json`
- HTTP Basic Bridge service credentials

The unversioned JSON-array request remains a migration input only until OE-M1
switches the OpenELIS producer. It is not a second source of desired state.

`compatibility.json` records when current unversioned registration and FHIR
behavior are read during migration and names the one-writer cutovers. Contract
publication is not a claim that the current runtime already emits every target
field.
