# Analyzer Contract v1

This directory is the executable BR-E0 boundary for OGC-1054. It evolves the
established `analyzer-defaults` profile system into one strict contract without
implementing profile lifecycle or runtime cutover.

## Profile contract

An analyzer profile has exactly two jobs:

1. define communication and runtime behavior for an analyzer type;
2. define the profile-owned defaults used to create a new Bridge connection of
   that type.

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
connections contain no OpenELIS catalog IDs, control lots, Westgard state,
release policy, or site lab units. Concrete connection values, including
credentials and FILE directories, belong only to the durable Bridge connection.

## Ownership boundary

- Bridge owns profile revisions, durable connections and their entered values,
  protocols, listeners, parsing, probes, control recognition, and FILE
  watching/transport.
- OpenELIS owns analyzer orchestration, lab units, local Test/Result Option
  bindings, verification/audit, activation intent, held results, review,
  alerts, and separate operational QC.
- A Bridge connection pins one profile ID/revision. Updating or duplicating a
  profile never moves an existing connection implicitly.
- OpenELIS stores the Bridge connection ID and acknowledged references. It may
  mediate generic create/update calls, but it does not persist analyzer-facing
  values or send a copied profile, classifier rules, local bindings, or
  operational-QC state.

## Durable connections and traffic

Create is idempotent by stable OpenELIS analyzer identity. Read responses expose
profile-derived generic fields, masked secrets, configuration revision,
readiness, probe evidence, and desired/actual runtime state. Update requires the
expected configuration revision. Probe is non-mutating. Activate and deactivate
are idempotent commands whose acknowledgements identify the exact connection,
profile, configuration, and runtime revisions applied by Bridge.

Normalized FHIR preserves raw analyzer code/value and identifies the durable
Bridge connection and pinned profile revision. OpenELIS resolves only
`Device.identifier[system="https://openelis-global.org/fhir/analyzer-connection-id"]`;
source addresses, sender tokens, names, and local analyzer IDs are context, not
routing authority. Every Observation has exactly one patient/control
classification and one control-recognition extension. A matching rule
evaluation must carry its complete rule and source evidence; explicit `NONE`
never invents an evaluation.

## Versioned artifacts

| Artifact                                 | Direction          | Runtime owner |
| ---------------------------------------- | ------------------ | ------------- |
| `analyzer-profile.schema.json`           | Bridge -> OpenELIS | BR-M1         |
| `connection-create.schema.json`          | OpenELIS -> Bridge | BR-M3         |
| `connection-update.schema.json`          | OpenELIS -> Bridge | BR-M3         |
| `analyzer-connection.schema.json`        | Bridge -> OpenELIS | BR-M3         |
| `connection-probe-request.schema.json`   | OpenELIS -> Bridge | BR-M3         |
| `connection-probe-result.schema.json`    | Bridge -> OpenELIS | BR-M3         |
| `connection-runtime-command.schema.json` | OpenELIS -> Bridge | BR-M3         |
| `connection-runtime-ack.schema.json`     | Bridge -> OpenELIS | BR-M3         |
| `normalized-fhir-bundle.schema.json`     | Bridge -> OpenELIS | BR-M4         |

Files under `fixtures/` are canonical producer/consumer inputs. A later
milestone may add optional fields compatibly; removing a field or changing its
required meaning requires a new major contract directory. There is no parallel
thin-profile schema, compatibility reader/writer, or bulk full-state
registration contract in the target path.
