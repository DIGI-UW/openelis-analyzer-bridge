# thermo-quantstudio, revision 1: evidence

`src/main/resources/analyzer-profiles/thermo-quantstudio.json` (contract 2.0), the first baseline revision
of this profile. It replaces the pre-baseline `quantstudio` profile, which the Bridge no longer
ships, and is not a later revision of it (rule 6).

## Documents

| Short name       | Document                                                                                                                              | Where                                                                               |
| ---------------- | ------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------- |
| QuantStudio spec | QuantStudio 5 / 7 Flex, Field Mapping & Integration Spec v1.3.1, 2026-03-06, validated against three real XLS exports from Madagascar | `openelis-work/designs/analyzer-integration/quantstudio-field-mapping-spec-v131.md` |
| Thermo Fisher    | QuantStudio Design & Analysis exports (Results sheet)                                                                                 | the export itself; no host-interface document exists                                |

## Tests

| Declared                                                                                                        | Defined in          |
| --------------------------------------------------------------------------------------------------------------- | ------------------- |
| test `VIH-1` (Target Name, FAM reporter), LOINC 20447-9, copies/mL, quantitative; its result is `Quantity Mean` | section 4.3 and 5.1 |
| test `IC` (Target Name, CY5 reporter), LOINC 89578-3, quantitative; the internal control row                    | section 5.1 and 8   |
| columns `Sample Name`, `Target Name`, `Quantity Mean`, `CT`, `Well Position`, `Task`                            | section 4.3         |

Revision 4 changes the contract only: each test now cites its source. The values are unchanged.

## Not verified

- A Thermo Fisher document for the export format; the spec is built from real exports, which
  check a site's configuration and do not define a profile (rule 19).
- LOINC 89578-3 is the standard code for internal control DNA; the spec does not give a LOINC.
