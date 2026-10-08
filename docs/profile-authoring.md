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

| Field                | Rule                                                                                                                                                                                                                  |
| -------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `test_code`          | the code the vendor suggests; a connection may override it                                                                                                                                                            |
| `loinc`              | the standard LOINC code of the test                                                                                                                                                                                   |
| `result_type`        | `quantitative`, `qualitative` or `text`; required                                                                                                                                                                     |
| `unit`               | the unit the instrument sends                                                                                                                                                                                         |
| `values`             | the raw values of a categorical test, exactly as the instrument sends them                                                                                                                                            |
| `value_codes`        | for each value, every standard coding the vendor gives it, as `{system, code}`; systems are LOINC `http://loinc.org`, SNOMED CT `http://snomed.info/sct` and CIEL `https://openconceptlab.org/orgs/CIEL/sources/CIEL` |
| `translations`       | for each value, the vendor's translations, all bound to that value; one text may belong to only one value                                                                                                             |
| `run_failure_values` | raw values that say the run produced no result (`ERROR`, `NO RESULT`); never answers, so a lab never maps them; may have translations                                                                                 |
| `call_component`     | on a quantitative test with a call, the component that takes the main record's call                                                                                                                                   |
| `components`         | the other records the instrument reports for the test                                                                                                                                                                 |
| `assay`              | `{name, version}` as the instrument reports them                                                                                                                                                                      |
| `source`             | `{document, section}`                                                                                                                                                                                                 |

`specimen_type_hint` and `result_value_hints` are not part of the contract. A categorical test has
`values`. A quantitative test may have `values` only when it names a `call_component`.

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

A profile without `resultParts` is read as before.

## Number format

`configDefaults.numberFormat` is the decimal separator the instrument writes, `.` or `,`. A saved
connection may replace it (`values.numberFormat`), because an instrument runs in the locale its lab
set. The Bridge turns the number into a FHIR decimal and keeps the raw text.

## Per-connection codes

A saved connection may set the code its instrument uses for an assay: `values.codeOverrides` maps
profile test codes to instrument codes. Results arrive under the profile's code, and outbound
orders use the connection's code. Each key must be a test the profile declares, and no two assays
may share a code.

## Checks

`mvn test` validates every shipped profile against the schema and checks that each baseline test
cites a source. A shipped baseline profile also replays the vendor's example messages
(`GeneXpertBaselineProfileTest`): every record is a declared test, component and value, and every
declared one is shown by an example.
