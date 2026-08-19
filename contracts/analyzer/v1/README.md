# Analyzer Contract v1

This directory is the executable BR-E0 boundary for OGC-1054. It evolves the
established `analyzer-defaults` profile system into one strict contract without
implementing profile lifecycle or runtime cutover.

## Profile contract

An analyzer profile has exactly two jobs:

1. define communication and runtime behavior for an analyzer type;
2. define the profile-owned defaults used to create an OpenELIS analyzer
   instance of that type.

`analyzer-profile.schema.json` preserves the established profile field families
and discriminates ASTM, HL7, and FILE requirements. Catalog-generated revision,
fingerprint, publication, lifecycle, and lineage values are isolated under
`catalog`; they are not authored runtime/default fields.

The complete ASTM and FILE fixtures are blocking compatibility inputs. Their
names and values are profile data only. Validator, catalog, runtime, consumer,
and mock code must remain generic and may not hardcode a profile ID/revision,
analyzer/model/manufacturer name, analyzer code, vendor value, mapping,
recognition rule, connection value, or profile-owned default.

`controlResultRecognition` describes only how Bridge recognizes analyzer
messages as controls. `RULES` contains OR matchers keyed by stable rule key;
`NONE` requires an explicit affirmation. It is not operational QC. Profiles and
registration contain no OpenELIS catalog IDs, control lots, Westgard state,
release policy, site lab units, credentials, or instance watch directory.

## Ownership boundary

- Bridge owns profile revisions and analyzer-facing runtime: protocols,
  listeners, parsing, probes, control recognition, and FILE watching/transport.
- OpenELIS owns analyzer orchestration, lab units, site-entered instance values,
  local Test/Result Option bindings, verification/audit, activation, held
  results, review, alerts, and separate operational QC.
- An OpenELIS analyzer pins one profile ID/revision. Updating or duplicating a
  profile never moves a configured analyzer implicitly.
- OpenELIS sends desired effective instance configuration. It does not send a
  copied profile, classifier rules, local bindings, or operational-QC state.

## Registration and traffic

Desired registrations and acknowledgements are objects keyed by OpenELIS
analyzer ID, so one candidate cannot receive contradictory outcomes. Connection
settings are protocol/mode-discriminated allowlists; arbitrary scalar settings
cannot carry foreign authority across the boundary.

Normalized FHIR preserves raw analyzer code/value and identifies the pinned
profile revision. Every Observation has exactly one patient/control
classification and one control-recognition extension. A matching rule evaluation
must carry its complete rule and source evidence; explicit `NONE` never invents
an evaluation.

## Versioned artifacts

| Artifact                               | Direction          | Runtime owner |
| -------------------------------------- | ------------------ | ------------- |
| `analyzer-profile.schema.json`         | Bridge -> OpenELIS | BR-M1         |
| `registration-sync.schema.json`        | OpenELIS -> Bridge | BR-M1         |
| `registration-sync-result.schema.json` | Bridge -> OpenELIS | BR-M1         |
| `normalized-fhir-bundle.schema.json`   | Bridge -> OpenELIS | BR-M4         |

Files under `fixtures/` are canonical producer/consumer inputs. A later
milestone may add optional fields compatibly; removing a field or changing its
required meaning requires a new major contract directory. There is no parallel
thin-profile schema, compatibility reader/writer, or legacy registration
contract in the target path.
