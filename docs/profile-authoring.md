# Writing an analyzer profile

A profile describes an instrument model and nothing about a site: its codes, the values it
declares, its result types, units and components, how it recognises control results, and its
connection defaults. It names no OpenELIS test, answer label, specimen or lab unit. The contract is
`contracts/analyzer/v1/analyzer-profile.schema.json`; a profile that sets `"schemaVersion": "2.0"`
is held to the rules below, and every profile written here (new, duplicated or updated) is 2.0.
Earlier revisions of a shipped profile stay as they were published, because a revision never
changes.

## Start from a template

Copy `templates/astm.json`, `templates/hl7.json` or `templates/file.json`, replace the
placeholders with the instrument's facts, and delete what the instrument does not send. A profile
written from a template validates with no other edits (`ProfileTemplatesTest`).

## Cite your sources

Each test names the vendor document and section that defines it in `source`. Sources, in order of
authority: the vendor's own LIS or host-interface document, then an integration spec, then code.
A real capture checks a site's setup; it is never a source. Write what you could not verify in
`docs/profiles/<profile-id>.md` under "Not verified" and leave it out of the profile. One note per
profile lists every code, value and record type with its section, where each result part sits, and
the value translations.

## Tests

| Field                | Rule                                                                                                                                                  |
| -------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------- |
| `test_code`          | the code the vendor suggests; a connection may override it                                                                                            |
| `loinc`              | the standard LOINC code of the test; shorthand for a `http://loinc.org` entry in `codings`                                                            |
| `codings`            | the codes that name the OpenELIS test, as `{system, code, display}`, in any system; a test has `loinc`, `codings` or both, and at most one LOINC code |
| `result_type`        | `quantitative`, `qualitative` or `text`; required                                                                                                     |
| `unit`               | the unit the instrument sends                                                                                                                         |
| `values`             | the raw values of a categorical test, exactly as the instrument sends them                                                                            |
| `value_codes`        | for each value, every coding the vendor gives it, as `{system, code, display}`                                                                        |
| `translations`       | for each value, the vendor's translations, all bound to that value; one text may belong to only one value                                             |
| `run_failure_values` | raw values that say the run produced no result (`ERROR`, `NO RESULT`); never answers, so a lab never maps them; may have translations                 |
| `call_component`     | on a quantitative test with a call, the component that takes the main record's call                                                                   |
| `components`         | the other records the instrument reports for the test                                                                                                 |
| `assay`              | `{name, version}` as the instrument reports them                                                                                                      |
| `source`             | `{document, section}`                                                                                                                                 |

`specimen_type_hint` and `result_value_hints` are not part of the contract. A categorical test has
`values`. A quantitative test may have `values` only when it names a `call_component`.

A coding's `system` is a standard system's URI (LOINC `http://loinc.org`, SNOMED CT
`http://snomed.info/sct`, CIEL `https://openconceptlab.org/orgs/CIEL/sources/CIEL`) or the
identifier a distro catalog names its own concepts by. A profile shipped with the Bridge cites
standard systems only; a distro's own profile may also cite its catalog's system. OpenELIS binds a
coding to the test or answer that carries the same `(system, code)`.

### A quantitative result with a call

HIV viral load is the worked case. The number goes on the test; the call (Detected, Not detected,
Invalid) goes on a call component of the same test. Not detected fills only the call, never `0`.
An off-scale result sent as the call with a `<` or `>` flag arrives as a quantity of the range's
limit with that comparator.

### Components

A component is a record the instrument reports for a test: an analyte call, a Ct, an end point,
an internal control, a log value. `{code, label, result_type, unit, values, value_codes,
translations, run_failure_values, sub_identity}`. The `code` is the stable code a local component
carries; defaults bind a record to the local component whose code equals it. `sub_identity` is the
record the component receives, in the vendor's HL7 sub-ID notation: the analyte and complementary
names (`HIV-1&Ct`, `&LOG`). The component with no `sub_identity` takes the main record's call. A
qualitative component that has a `sub_identity` declares its `values`.

## A file's test-code column

A tabular file names each row's test in the column `column_mapping` marks `testCode`, or, when
no column does, every row takes the connection's file-wide test code. When that column carries
something other than a test code, `test_code_values` says what each value means. The Madagascar
FluoroCycler workbook numbers its detection channels:

```json
"column_mapping": { "Sample ID": "sampleId", "Col": "testCode", "Calc. Conc.": "result", "Result": "interpretation" },
"test_code_values": { "1": "VIH-1", "2": "IC" }
```

Every translated code must be a declared test. A value the map does not name is kept as the file
carries it, so OpenELIS holds the row as a code nobody has mapped rather than guessing.

## Where each part of a result sits

`configDefaults.extractionOverrides.resultParts` gives the field that holds each part. ASTM names
fields as `R.3.4` (record, field, component):

```json
"resultParts": {
  "testCode": "R.3.4", "assayName": "R.3.5", "assayVersion": "R.3.6",
  "analyte": "R.3.7", "complement": "R.3.8",
  "call": "R.4.1", "number": "R.4.2", "unit": "R.5", "range": "R.6", "flag": "R.7",
  "status": "R.9", "operator": "R.11", "started": "R.12", "completed": "R.13",
  "instrument": "R.14", "note": "C.4", "patientId": "P.5", "patientName": "P.6",
  "specimenDescriptor": "O.16"
}
```

With result parts, `resultRecordSelection` is `ALL`: every R record is a result, and a record
with neither a call nor a number is skipped. A record that names its assay is the main result, so
its analyte field is read as a result name and not as a sub-identity. The Bridge emits one
Observation per record, with the number in `valueQuantity`, an off-scale flag as
`Quantity.comparator`, a call beside a number as an interpretation, the flags as sent, the assay
as `method`, the operator as `performer`, comments as `note`, and a run failure as
`dataAbsentReason` with no value.

HL7 names fields by the paths control recognition reads, `SEG.field[.component[.subcomponent]]`.
Observation parts are OBX paths; `completed` and `operator` may also name the OBR before the
observation, `patientId` and `patientName` the PID, `specimenDescriptor` the OBR or SPM, and
`note` the NTE that follows it:

```json
"resultParts": {
  "testCode": "OBX.3.1", "subIdentity": "OBX.4", "value": "OBX.5", "unit": "OBX.6.1",
  "range": "OBX.7", "flag": "OBX.8", "status": "OBX.11", "completed": "OBX.14",
  "operator": "OBX.16", "instrument": "OBX.18", "note": "NTE.3",
  "patientId": "PID.3.1", "patientName": "PID.5", "specimenDescriptor": "SPM.4"
}
```

OBX-2 decides whether `value` is a number (`NM`, `SN`) or a call, and each repetition of the flag
field (`H~A`) is a flag. An HL7 `resultRecordSelection` is `ALL`, or `EXCLUDE` with a
`targetField` and the `values` that mark an OBX as something other than a result, such as an
instrument's settings, histogram images or discriminators. Everything else is a result, so a code
the profile does not declare still reaches OpenELIS, which holds it for review.

A profile without `resultParts` is read as before.

## Warnings about the sample

Some instruments send yes or no warnings about the sample as records of their own: a hematology
analyzer's alarms (Anemia, Platelet Clump?), a PCR run's internal-control validity. `sample_flags`
names them: the codes, the value that means raised, and a name per code where the record carries
none. A record carrying one of these codes is never a result. A raised one goes in the bundle as
an Observation classified `SAMPLE_FLAG`, and OpenELIS shows it with the sample's results and keeps
it as a note on the analysis once accepted; one not raised is dropped. The record's code and value
are read where a result's are, whatever the protocol; the instrument's name for the code, when it
sends one, sits beside the code (OBX-3.2, R.3.5):

```json
"sample_flags": { "raisedValue": "T", "codes": ["12014", "15180-3"] }
```

A file carries no name, so a FILE profile names each flag:

```json
"sample_flags": { "raisedValue": "Invalid", "codes": ["IC"], "names": { "IC": "Internal control invalid" } }
```

A profile without `sample_flags` has no record that is a warning.

## Number format

`configDefaults.numberFormat` is the decimal separator the instrument writes, `.` or `,`. A saved
connection may replace it (`values.numberFormat`), because an instrument runs in the locale its lab
set. The Bridge turns the number into a FHIR decimal and keeps the raw text.

## Per-connection codes

A saved connection may set the code its instrument uses for an assay: `values.codeOverrides` maps
profile test codes to instrument codes. Results arrive under the profile's code, and outbound
orders use the connection's code. Each key must be a test the profile declares, and no two assays
may share a code.

## Ship a profile

A shipped profile is one file per revision. Beside the authored content it carries
`profileMeta.id`, a stable name in `[a-z0-9][a-z0-9._-]+` that is not a `site.` id, and a
`catalog` block:

```json
"catalog": {
  "revision": 1,
  "source": "SHIPPED",
  "status": "ACTIVE",
  "publishedAt": "2026-10-20T00:00:00Z",
  "publishedBy": "distribution"
}
```

Write no fingerprints: the Bridge computes `revisionFingerprint` and `recognitionFingerprint`
from the file's content when it loads it and replaces any the file carries. A file can be edited
under its revision: after a restart its connections keep running on the edited content, and
OpenELIS holds only the results whose reading changed (a code's unit, result type, parts,
values or translations, or how the type reads every result) until a person confirms the change.
A name, hint, coding or new assay holds nothing. Publish the next revision, in a new file with
`revision` one higher, for a change each lab should adopt through OpenELIS's review screen, such
as a renamed or split code; keep the earlier file so connections pinned to it keep restoring.

To take a profile authored in the Bridge UI into a shipped set, copy the `profile` object out
of its revision file in the profile catalog directory
(`<bridge-data>/profile-catalog/<profileId>/<revision>.json`), give it the stable id, replace
its `catalog` block with the one above, and drop the two fingerprint fields. The copy is a new
profile at revision 1; the site's `site.` profile and its connections are untouched.

## Checks

`mvn test` validates every shipped profile against the schema and checks that each baseline test
cites a source. A shipped baseline profile also replays the vendor's example messages
(`GeneXpertBaselineProfileTest`, `MindrayBc5380BaselineProfileTest`): every record is a declared
test, component and value, and every declared one is shown by an example.
