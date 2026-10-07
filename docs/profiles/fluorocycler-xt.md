# fluorocycler-xt, revision 5: evidence

`src/main/resources/analyzer-profiles/fluorocycler-xt-v5.json` (contract 2.0).

## Documents

The FluoroCycler XT exports no host-interface file. Labs copy results from FluoroSoftware XT into
a template, so the source is the integration spec the template comes from, not a Bruker document.

| Short name | Document | Where |
| ---------- | -------- | ----- |
| FluoroCycler spec | Bruker FluoroCycler XT, Molecular PCR Flat File Import Spec v1.0, 2026-03-06 | `openelis-work/designs/analyzer-integration/fluorocycler-xt-integration-spec-v1.0.md` |

## Tests and values

| Declared | Defined in |
| -------- | ---------- |
| test `VIH-1`, LOINC 20447-9, unit copies/mL, quantitative, with a call | section 4.3 (`CalcConc` is the number when it is above 0, else `Interpretation` is the result) |
| values `DETECTED`, `NOT DETECTED`, `INVALID`, `INDETERMINATE` | section 3.2 and 4.2 (`Interpretation` value map) |
| `DETECTED` LOINC LA11882-0, SNOMED 260373001, CIEL 1301; `NOT DETECTED` LA11883-8, 260415000, CIEL 1302; `INVALID` LA15841-2, CIEL 163611; `INDETERMINATE` SNOMED 82334004, CIEL 1138 | standard codings read from the LOINC answer lists, SNOMED and the CIEL release of 28 April 2026 (2026-10-05); no LOINC answer exists for Indeterminate |
| columns `Sample ID`, `TargetName`, `Calc. Conc.`, `Result`, `Type`, `WellPosition` | section 3.1 (already mapped by revision 4) |

The profile has no translations: the template is filled in by the lab in English. The specimen
type hint revision 4 carried is removed (rule 1).

## Not verified

- Other targets the template carries (MPox, VACV, IC, HBV and the LightMix assays): the spec
  describes them as examples, not a fixed menu, so a lab adds them as per-analyzer rows.
- Any Bruker document defining the file: none exists, since the file is hand-prepared.
