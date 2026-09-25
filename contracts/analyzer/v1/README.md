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
an explicit empty `rules: {}` represents recognition that has not been configured.
It yields `RULES` / `NOT_EVALUATED`, no rule evidence, and no matched control.
This preserves existing unclassified traffic without asserting that an instrument
never sends controls. Catalog consumers must display that recognition is not
configured; they must not describe it as evaluated or as confirmed no-controls.
Absent or malformed rules remain invalid. `NONE` still requires the explicit
`affirmedNoControlResults: true` affirmation. This is not operational QC. Profiles and
connections contain no OpenELIS catalog IDs, control lots, Westgard state,
release policy, or site lab units. Concrete connection values, including
credentials and FILE directories, belong only to the durable Bridge connection.

### HL7 recognition field paths

HL7 field rules use `SEG.field`, `SEG.field.component`, or
`SEG.field.component.subcomponent`, with one-based positions. `MSH.1` is the
field separator and `MSH.2` is the encoding-character declaration; neither is
split into component evidence. Parsing uses the separators declared by MSH,
with standard separators for headerless segment fixtures. Whole-field values
retain repetitions; a component path selects the first repetition because the
path grammar has no repetition index.

Recognition is evaluated for each OBX using that observation's fields and the
preceding segment context. A subsequent segment replaces all previous fields
for that segment type, including fields omitted from the new segment. Later
observations and orders never reclassify earlier results. This field extraction
does not activate an HL7 listener or establish a sender's routing authority.

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

### HTTP tabular input

HTTP CSV uses the existing FILE profile contract, not ASTM records. The profile
declares a required `transport` connection field offering `HTTP` and a required
`host` field for the sender's source IP. These values are persisted with the
connection; the shared HTTP input endpoint resolves only active, uniquely owned
sender bindings. Unregistered or ambiguous addresses fail closed. Forwarded
headers follow the explicitly trusted-proxy rules in the deployment README.

The pinned profile supplies CSV/TSV format, delimiter, skipped rows, column
mapping, result-value selection, and control recognition. HTTP activation does
not start a folder watcher. `filePattern` remains profile data for the existing
FILE contract but is not used for HTTP delivery. No shipped profile is changed
implicitly; an HTTP-capable profile must explicitly offer these connection fields.
Inbound HTTP cannot be actively probed as a remote instrument endpoint: profiles
should declare `connectionTest: false`, and actual input delivery supplies traffic
evidence. A direct probe request reports failure with `http.input.verify.with.delivery`.

Each accession is forwarded separately. The request succeeds only after all
accessions are accepted. HTTP tabular retries use the FILE identity below, hashing
the UTF-8 bytes of the request text consumed by the parser, so replaying a partially
accepted request preserves every accession's delivery identity.

### FILE assay selection

`configDefaults.fileTestCode` optionally supplies the assay for rows without a
mapped test code. A profile may expose the same key as a connection field so a
saved connection can override its default. The selected code must be a primary
`default_test_mappings` code in that pinned profile. A nonblank row-level test code
still takes precedence; selection never rewrites codes supplied by the analyzer.
Without a selection, the existing single-primary-test fallback applies. Multiple
primary tests require a row-code column or an explicit selection. The selected
value is captured with the durable file receipt and survives retries and restart.

### FILE delivery identity and retries

`Bundle.identifier` uses the analyzer-message-id system and identifies a delivery,
not a parsing attempt. For FILE traffic its value is `file-v1:` followed by a
SHA-256 digest of the connection ID, lowercase SHA-256 content hash of the exact
file bytes parsed, and accession number. Each UTF-8 component is prefixed by its
four-byte big-endian byte length before hashing. File paths, process lifetime,
and generated FHIR resource IDs do not participate in this identity.

Retrying a partially delivered file, a lost acknowledgment, or the same bytes
under a new filename therefore preserves the per-accession identity. Different
connections, file contents, or accessions produce distinct delivery identities.
OpenELIS must record the connection/message receipt transactionally with result
processing and return success for a previously accepted delivery without staging
results or processing operational QC again. This receipt must survive result
review and deletion of staging rows. Intentional reprocessing of held results is
a separate OpenELIS operation, not a fresh FILE delivery or a receipt reset.

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
