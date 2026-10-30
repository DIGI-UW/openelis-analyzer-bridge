# Analyzer Contract v1

This directory is the versioned contract between the Bridge and OpenELIS:
analyzer profiles and the profile catalog, saved connections, connection probes,
runtime commands, and the normalized result bundle. `AnalyzerContractArtifactsTest`
checks the schemas and fixtures against the code.

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

### Specimen context for clinical defaults

A test mapping may supply `specimen_type_hint`: an assay-supported specimen name
used by OE2 when resolving the default local test. It is optional and nonblank.
OE2 matches it against actual active specimen associations alongside LOINC, not
against a test-name suffix. It does not create a specimen, constrain message
transport, change an existing clinical binding, or replace user customization.
Missing or ambiguous local matches remain available for normal mapping review.
Profiles without the hint retain the existing resolution behavior.

GeneXpert revision 6 adds `Plasma` for the HIV-VL default. The assay's
[Cepheid instructions, 301-3068 Rev. N](https://infomine.cepheid.com/sites/default/files/2023-08/Xpert%20HIV-1%20Viral%20Load%20ENGLISH%20IFU%20301-3068%2C%20Rev.%20N.pdf)
describe quantitative HIV-1 RNA testing in EDTA or ACD plasma. Revisions 1–5
remain unchanged; configured connections do not move to revision 6 implicitly.
This change does not settle other assays' specimen or result-value semantics.

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

### HL7 result parts and record selection

An HL7 profile locates each part of a result the way an ASTM profile does, in
`configDefaults.extractionOverrides.resultParts`, with the same field paths:
`testCode` and `value` (required), `subIdentity`, `unit`, `range`, `flag`,
`status` and `instrument` in the OBX; `completed` and `operator` in the OBX or
the OBR before it; `patientId` and `patientName` in the PID (the name field is
read as family and given); `specimenDescriptor` in the OBR or SPM; and `note` in
an NTE that follows the observation. OBX-2 decides whether the value is a number
(`NM`, `SN`) or an answer. Repetitions of the flag field (`H~A`) are separate
flags. An observation with no value is not a result. A profile without
`resultParts` is read as before: OBX-3, OBX-5 and OBX-6 only.

`resultRecordSelection` says which OBX segments carry results. `ALL`, the
default, reads every one. `EXCLUDE` names a `targetField` and the `values` that
mark an OBX as something else, such as an instrument's settings or histogram
images; every other OBX is a result or, under `sample_flags`, a warning, including one under a code the
profile does not declare, which OpenELIS holds for review.

## Record kinds

What an instrument says about a sample is of one of three kinds, and a profile declares each
record under exactly one section (the Bridge refuses a profile that declares a code under two):

| Kind                               | Section                                                 | Where it ends in OpenELIS                                 |
| ---------------------------------- | ------------------------------------------------------- | --------------------------------------------------------- |
| a result for a test the lab orders | `default_test_mappings` (with its parts and components) | the ordered analysis, or held for placement               |
| a value on a control material      | `controlResultRecognition` (which samples are controls) | the QC module                                             |
| a warning about the sample         | `sample_flags`                                          | the review row and a note on the analysis; never a result |
| a record the instrument also sends | `resultRecordSelection` (`EXCLUDE`)                     | not received                                              |

A record the profile does not declare at all reaches OpenELIS as a code nobody has mapped, which
the lab maps or marks Do not receive. Where a record's parts sit on the wire is a reading setting
(`resultParts`, `column_mapping`), never a kind; a new kind needs a rule in the OpenELIS analyzer
spec before a profile field.

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
routing authority. The Device's optional `analyzer-profile-fingerprint`
extension is the fingerprint of the profile content the message was read with;
OpenELIS preserves it as provenance; it is not an import or mapping-approval gate.
Every Observation has exactly one patient/control
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

| Artifact                                 | Direction          |
| ---------------------------------------- | ------------------ |
| `analyzer-profile.schema.json`           | Bridge -> OpenELIS |
| `profile-catalog-entry.schema.json`      | Bridge -> OpenELIS |
| `profile-catalog-response.schema.json`   | Bridge -> OpenELIS |
| `connection-create.schema.json`          | OpenELIS -> Bridge |
| `connection-update.schema.json`          | OpenELIS -> Bridge |
| `analyzer-connection.schema.json`        | Bridge -> OpenELIS |
| `connection-probe-request.schema.json`   | OpenELIS -> Bridge |
| `connection-probe-result.schema.json`    | Bridge -> OpenELIS |
| `connection-runtime-command.schema.json` | OpenELIS -> Bridge |
| `connection-runtime-ack.schema.json`     | Bridge -> OpenELIS |
| `normalized-fhir-bundle.schema.json`     | Bridge -> OpenELIS |

Files under `fixtures/` are canonical producer/consumer inputs. Optional fields
may be added compatibly; removing a field or changing its required meaning
requires a new major contract directory. There is no parallel thin-profile
schema, compatibility reader/writer, or bulk full-state registration contract.

Readers on both sides ignore fields they do not know, so an added optional
field reaches an older reader without breaking it; each reader checks the
fields it depends on, and refuses an unsupported `schemaVersion`. The Bridge
refuses by name the fields OpenELIS must never send (`openelisTestId`,
`openelisResultOptionId`, `labUnitId`, `controlLots`, `qcRules`, `westgard`,
`operationalQc`, and the reserved keys under `values`), and stores only the
pin fields it knows. Connection responses are checked against their schema
before they leave the Bridge.

### Categorical answer hints

A test mapping may supply optional `result_value_hints`, an object whose keys
are exact declared raw `values` and whose values are equivalent clinical answer
labels. A consumer first tries the raw label; when no local answer matches, it
may try the explicit hint. Case and whitespace normalization are permitted;
multiple matches stay unresolved. A hint neither changes the instrument value
nor a saved site binding, and does not create a local clinical answer. Bridge
retains and fingerprints the field with the profile; ingestion retains the raw
value. Profiles without hints retain their existing behavior.

GeneXpert revision 7 supplies COVID POSITIVE and NEGATIVE hints for RNA detected
and not detected, with `Respiratory Swab` context to distinguish the existing
respiratory clinical test from other specimen-specific tests. The [Xpert Xpress SARS-CoV-2 instructions, 302-3562 Rev G,
section 16](https://www.cepheid.com/Package%20Insert%20Files/Xpress-SARS-CoV-2/Xpert%20Xpress%20SARS-CoV-2%20Assay%20ENGLISH%20Package%20Insert%20302-3562%20Rev.%20G.pdf)
distinguish detection outcomes from INVALID, ERROR and NO RESULT. This revision
does not equate ERROR with Invalid or guess a translation for INDETERMINATE.
The curated host-code vocabulary still needs actual instrument qualification;
manufacturer display labels are not proof of captured ASTM bytes. Revisions 1–6
remain unchanged. Full producer/consumer/runtime qualification is required
before a consuming release updates its pin.

### FluoroCycler HIV default specimen

FluoroCycler revision 4 adds a Plasma default binding hint to its existing
`VIH-1` concentration row. Revision 3 remains unchanged. Parsing, units, control
recognition and directory configuration are unchanged; this does not convert
units or reinterpret the raw `Valid` interpretation as a concentration.

[Biocentric GENERIC HIV Charge Virale](https://www.biocentric.com/copy-of-generic-hiv-charge-virale-en)
describes plasma and copies/mL. The
[current Bruker version 2.0 assay](https://www.bruker.com/en/products-and-solutions/molecular-diagnostics/assays/hiv-hepatitis/generic-hiv-1-viral-load.html)
also uses plasma but reports IU/mL. The existing copies/mL profile is not a
qualified IU/mL assay profile; installations must select a profile matching
their assay/export. The specimen hint supplies a local binding default, not
specimen evidence from the message. Synthetic workbook compatibility is not
physical instrument or assay qualification.

### Normalized result version 1.1: interpret once

The Device advertises `analyzer-contract-version: 1.1`. Bridge resolves sender,
profile aliases, connection code overrides, decimal format, qualitative translations,
record parts and control recognition using the context retained with raw receipt.
Non-FILE receipts persist that resolved context atomically with raw data; FILE receipts
retain their parser context including answer definitions. Restart and delivery retry
reuse it. Already rendered payloads are never reinterpreted on retry.

`Observation.valueQuantity` is a decimal with its original comparator and unit.
A qualitative answer is `valueCodeableConcept` when declared standard codings exist,
otherwise `valueString`. Its canonical profile identity is also carried in
`analyzer-canonical-answer`; `analyzer-raw-value` retains the original spelling.
A number plus a call uses `interpretation` with `analyzer-result-part: call`;
informational flags use `analyzer-result-part: flag`. The role extension uses
`valueCode`. Components retain their explicit sub-identity. Failed runs use
`dataAbsentReason` with the standard `error` code and no result value. Declared
clinical codings accompany the test and answer; undeclared input is not assigned
invented terminology. A conflicting vendor translation is held in Bridge.

OpenELIS validates the delivered contract, maps canonical identities to approved
local test/component/answer targets, and handles placement, operational QC, review,
receipts and audit. It does not fetch a current or historical Bridge profile to
approve a completed result. Local target changes still require local approval.
Raw vendor text is audit evidence only. It cannot override a canonical answer,
exclude a typed number, or choose a local answer mapping. Obsolete vendor-spelling
mapping rows may be removed through the local editor.

Upgrade OpenELIS before enabling a 1.1 producer: older readers can ignore the new
role marker and mistake an unfamiliar call next to a quantity for a flag. The new
reader accepts retained 1.0 bundles using their original standard interpretation
codes; it never guesses missing qualitative translations. Unknown older answers
remain held for local mapping. Unsupported contract versions are rejected.
Legacy unrendered raw receipts without decoding context stay in the existing
outbox review flow. An explicit Retry can resolve an as-yet unresolved receipt
against the configured connection; once resolved, its interpretation is immutable.
This is not a bulk reprocessor and never changes accepted OpenELIS results.

#### Canonical source identity and quantities

Within a profile, `(profileId, test code, sub-identity, canonical answer)` names a
stable reported meaning. Parser revisions do not change that identity. A genuinely
different test or answer must receive a different identity; do not reuse a code
for an unrelated meaning. OpenELIS applies the approved local mapping for that
scope. A result from another profile is retained as unresolved local work rather
than rejected merely because the instrument now uses another profile.

A quantity is already a FHIR decimal, with its reported unit and comparator.
Bridge owns number-format interpretation for ASTM, HL7, FILE and HTTP tabular
input. FILE receipts retain the decimal setting and failure rules, so restart
cannot reinterpret them using today's connection. Tabular extraction does not
assign numeric meaning before those settings are applied. No implicit unit
conversion is performed. The lab approves the declared quantity meaning and
local target during mapping; display-label equality is not clinical equivalence.

Version 1.1 requires explicit `call`/`flag` roles and supported typed values;
missing roles never fall back to the 1.0 vocabulary. Recovery uses the accepted
bundle's version as well. Recognition hashes are optional provenance in the
result payload; explicit classification and consistent recognition evidence remain
required. Receipt identity prevents replay; separate messages remain reviewable
even if their results have equal values or timestamps.

## Setup reception (normalized contract 1.2)

`START_SETUP` starts the saved connection with purpose `SETUP`; `ACTIVATE`
selects `ROUTINE`. Successful setup acknowledgments carry `receptionPurpose:
SETUP`. The purpose is part of the runtime fingerprint and persisted activated
configuration. It is captured in the immutable receipt interpretation, so
rebuilding or retrying a message after routine activation cannot change it.

The normalized Device carries contract version `1.2` and exactly one
`https://openelis-global.org/fhir/StructureDefinition/analyzer-delivery-purpose`
`valueCode`, either `SETUP` or `ROUTINE`. All 1.1 interpretation rules continue
to apply. OpenELIS holds SETUP deliveries as non-clinical evidence regardless
of the current analyzer state. Old 1.0/1.1 receipts retain routine semantics.
Stop setup reception before rolling back to a consumer without 1.2 support.
