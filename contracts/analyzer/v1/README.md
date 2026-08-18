# Analyzer Contract v1

This directory is the executable BR-E0 contract boundary for OGC-1054. It
defines target wire shapes and canonical compatibility fixtures without
implementing the Bridge profile lifecycle (BR-M1) or safe-traffic runtime
changes (BR-M4).

## Authority boundary

- Bridge owns portable profile identity, revision, protocol behavior, parsing,
  normalized analyzer concepts, control-result recognition, and runtime
  transport.
- OpenELIS owns analyzer instances, laboratory units, local Test and Result
  Option bindings, mapping verification and audit, operational QC, readiness,
  held results, and downstream clinical processing.
- Portable profiles contain no OpenELIS database identifiers.
- Registration sends desired analyzer-instance configuration. It does not send
  OpenELIS classifier rules, control lots, Westgard definitions, or any other
  operational-QC state, and it does not transfer local catalog ownership to
  Bridge.
- Normalized FHIR preserves raw analyzer code and value even when a portable
  normalized coding is known.

Every portable profile revision declares one `controlResultRecognition` mode.
`RULES` contains an object of one or more OR matchers keyed by stable rule key;
the object shape makes duplicate keys invalid, and field matchers require
`targetField`. `NONE` contains no rules and requires
`affirmedNoControlResults: true`. Missing, unknown, empty, or unaffirmed modes
are invalid.

## Versioned artifacts

| Artifact                               | Direction          | Runtime owner |
| -------------------------------------- | ------------------ | ------------- |
| `portable-profile.schema.json`         | Bridge -> OpenELIS | BR-M1         |
| `registration-sync.schema.json`        | OpenELIS -> Bridge | BR-M1         |
| `registration-sync-result.schema.json` | Bridge -> OpenELIS | BR-M1         |
| `normalized-fhir-bundle.schema.json`   | Bridge -> OpenELIS | BR-M4         |

The files under `fixtures/` are canonical producer/consumer inputs. Bridge and
OpenELIS must run the same fixtures. A later milestone may add fields
compatibly, but changing required meaning or removing a field requires a new
major contract directory.

`compatibility.json` records when current unversioned registration and FHIR
behavior are read during migration and names the one-writer cutovers. Contract
publication is not a claim that the current runtime already emits every target
field.
