# hain-fluorocycler-xt, revision 1: evidence

`src/main/resources/analyzer-profiles/hain-fluorocycler-xt.json` (contract 2.0), the first baseline revision
of this profile. It replaces the pre-baseline `fluorocycler-xt` profile, which the Bridge no longer
ships, and is not a later revision of it (rule 6).

## Documents

The FluoroCycler XT exports no host-interface file. Labs copy results from FluoroSoftware XT into
a template, so the source is the integration spec the template comes from, not a Bruker document.

| Short name        | Document                                                                     | Where                                                                                 |
| ----------------- | ---------------------------------------------------------------------------- | ------------------------------------------------------------------------------------- |
| FluoroCycler spec | Bruker FluoroCycler XT, Molecular PCR Flat File Import Spec v1.0, 2026-03-06 | `openelis-work/designs/analyzer-integration/fluorocycler-xt-integration-spec-v1.0.md` |

## Tests and values

| Declared                                                                                                                                                                              | Defined in                                                                                                                                             |
| ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------ |
| test `VIH-1`, LOINC 20447-9, unit copies/mL, quantitative, with a call                                                                                                                | section 4.3 (`CalcConc` is the number when it is above 0, else `Interpretation` is the result)                                                         |
| values `DETECTED`, `NOT DETECTED`, `INVALID`, `INDETERMINATE`                                                                                                                         | section 3.2 and 4.2 (`Interpretation` value map)                                                                                                       |
| `DETECTED` LOINC LA11882-0, SNOMED 260373001, CIEL 1301; `NOT DETECTED` LA11883-8, 260415000, CIEL 1302; `INVALID` LA15841-2, CIEL 163611; `INDETERMINATE` SNOMED 82334004, CIEL 1138 | standard codings read from the LOINC answer lists, SNOMED and the CIEL release of 28 April 2026 (2026-10-05); no LOINC answer exists for Indeterminate |
| columns `Sample ID`, `TargetName`, `Calc. Conc.`, `Result`, `Type`, `WellPosition`                                                                                                    | section 3.1 (already mapped by the pre-baseline `fluorocycler-xt`)                                                                                     |

The profile has no translations: the template is filled in by the lab in English. It declares no
specimen type hint (rule 1).

## Not verified

- Other targets the template carries (MPox, VACV, IC, HBV and the LightMix assays): the spec
  describes them as examples, not a fixed menu, so a lab adds them as per-analyzer rows.
- Any Bruker document defining the file: none exists, since the file is hand-prepared.

## Revision 2: control recognition

`src/main/resources/analyzer-profiles/hain-fluorocycler-xt-v2.json` changes only `controlResultRecognition`.

- It keeps the `Type` rules (`Positive`, `Negative`, `Standard`). The Madagascar site file has a
  `Type` column, with `Unknown` on patient rows (DIGI-UW/analyzer-mock-server commit 835261c, which
  took the column shape from that file).
- It drops revision 1's `C+` and `C-` Sample ID prefixes, which no document or site file uses.

Not verified: the value a control row carries in `Type`. No file we have includes a control, and
no Bruker document describes the export. One site run with a positive and a negative control
settles it; until then the operands stay as revision 1 had them.
