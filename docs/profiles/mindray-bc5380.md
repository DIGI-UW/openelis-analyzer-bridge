# mindray-bc5380, revision 1: evidence

Every code, record and field the profile declares, with the section of the vendor document that
defines it. A row with no vendor document is listed under "Not verified" and is not in the profile.
Revision 1 is `src/main/resources/analyzer-profiles/mindray-bc5380.json` (contract 2.0). It is the
first baseline revision of this profile.

## Documents

| Short name | Document                                                                                       | Where                                                         |
| ---------- | ---------------------------------------------------------------------------------------------- | ------------------------------------------------------------- |
| BC-5380    | Mindray BC-5380 Auto Hematology Analyzer Operator's Manual, H-046-001572-00 version 4.0, appendix C "Communication" | `openelis-work/assets/vendor-manuals/bc5380-lis-protocol.pdf` |

Appendix C defines the HL7 v2.3.1 interface: C.1 the MLLP framing over TCP (the analyzer connects
to the LIS and sends UTF-8), C.3.2 the segments, C.3.3 a complete result message and an X-R QC
message, and C.5 the coding of OBR-4 (Table 9), of each OBX (Table 10) and the units (Table 11).
The two example messages are in `src/test/resources/mindray-examples/`, wrapped lines joined and
page breaks removed; `MindrayBc5380BaselineProfileTest` replays both.

## Tests

The profile declares the analysis results of Table 10, the parameters the analyzer measures. Each
code is the OBX-3 identifier the analyzer sends; Mindray gives the LOINC code for each, except PCT.

| Profile test | Parameter | LOINC                                    | Unit in C.3.3 |
| ------------ | --------- | ---------------------------------------- | ------------- |
| `6690-2`     | WBC       | 6690-2                                   | 10\*9/L       |
| `704-7`      | BAS#      | 704-7                                    | 10\*9/L       |
| `706-2`      | BAS%      | 706-2                                    | none          |
| `751-8`      | NEU#      | 751-8                                    | 10\*9/L       |
| `770-8`      | NEU%      | 770-8                                    | none          |
| `711-2`      | EOS#      | 711-2                                    | 10\*9/L       |
| `713-8`      | EOS%      | 713-8                                    | none          |
| `731-0`      | LYM#      | 731-0                                    | 10\*9/L       |
| `736-9`      | LYM%      | 736-9                                    | none          |
| `742-7`      | MON#      | 742-7                                    | 10\*9/L       |
| `5905-5`     | MON%      | 5905-5                                   | none          |
| `26477-0`    | ALY#      | 26477-0                                  | 10\*9/L       |
| `13046-8`    | ALY%      | 13046-8                                  | none          |
| `789-8`      | RBC       | 789-8                                    | 10\*12/L      |
| `718-7`      | HGB       | 718-7                                    | g/L           |
| `787-2`      | MCV       | 787-2                                    | fL            |
| `785-6`      | MCH       | 785-6                                    | pg            |
| `786-4`      | MCHC      | 786-4                                    | g/L           |
| `788-0`      | RDW-CV    | 788-0                                    | none          |
| `21000-5`    | RDW-SD    | 21000-5 (deprecated in LOINC; see below) | fL            |
| `4544-3`     | HCT       | 4544-3                                   | none          |
| `777-3`      | PLT       | 777-3                                    | 10\*9/L       |
| `32623-1`    | MPV       | 32623-1                                  | fL            |
| `32207-3`    | PDW       | 32207-3                                  | none          |
| `10002`      | PCT       | 51637-7 (Mindray gives none; LOINC)      | mL/L          |

All are quantitative. The LOINC codes were checked against the NLM LOINC service on 2026-10-10.
The unit is the one the C.3.3 message sends. Table 11 makes the unit a setting of the instrument
(for example 10^9/L or 10^3/uL), and the QC example sends the percentages as `%` where C.3.3 sends
fractions with no unit, so a site whose instrument shows other units overrides them on its
analyzer, as with any default.

## Where each part sits

| Part                | Field  | Defined as                                                                     |
| ------------------- | ------ | ------------------------------------------------------------------------------ |
| test code           | OBX-3.1 | Table 7: "ID^Name^EncodeSys"; the ID and the coding system identify a parameter |
| value               | OBX-5  | Table 7; OBX-2 (`NM`, `IS`, `ST`, `ED`) says what it is                        |
| unit                | OBX-6.1 | Table 7, Table 11                                                             |
| range               | OBX-7  | Table 7: "lower limit-upper limit", "< upper limit" or "> lower limit"        |
| flag                | OBX-8  | Table 7: `N`, `A`, `H`, `L`, two joined by `~` (`H~A`)                         |
| status              | OBX-11 | Table 7: `F`, final                                                            |
| completed           | OBR-7  | Table 6: run time                                                              |
| operator            | OBR-32 | Table 6: principal result interpreter, the tester                              |
| specimen ID         | OBR-3  | Table 6: filler order number, the sample ID in a result message               |
| specimen descriptor | OBR-15 | Table 6: sample source, `BLDV` venous or `BLDC` capillary blood                |
| patient             | PID-3.1, PID-5 | Table 4                                                                |
| sender              | MSH-3  | Table 1: sending application, `BC-5380`                                        |

A QC message carries `Q` in MSH-11 (C.3.3, "QC message"); the profile's control recognition reads
it, so every result of a QC message is a control.

## Records that are not results

The record selection leaves out the OBX segments Table 10 lists as "Other data" and the histogram
and scattergram data: take mode, blood mode and test mode (08001 to 08003), age (30525-0), remark
(01001), reference group (01002), QC level (05001), and the WBC, RBC, PLT and DIFF histogram and
scattergram discriminators, lengths, adjustment marks and images (15000 to 15200). Every other OBX
is a result, so the alarms and the microscope exam results below still reach OpenELIS, which holds
them for review until a lab maps them.

## Discrepancies in the vendor document

- Mindray gives 21000-5 for RDW-SD; LOINC lists it as deprecated, and the NLM service names no
  replacement. The profile keeps Mindray's code.
- The C.3.3 message sends the percentages and HCT as fractions with no unit; the QC example sends
  them in `%`. Both follow the instrument's unit setting.

## Not verified, and not in the profile

- The large immature cells, `10000` (LIC#) and `10001` (LIC%): Mindray gives no LOINC code and
  LOINC has none for them.
- The research parameters `10003` to `10006` (GRAN-X, GRAN-Y, GRAN-Y(W), WBC-MCV): Mindray gives
  no LOINC code, unit or definition.
- The abnormal alarm information (12000 to 12018 and the LOINC-coded alarms such as 15180-3
  Hypochromia): values `T` and `F`, flags about the sample rather than measured results; where they
  belong in OpenELIS is not decided.
- The microscope exam data (Table 10, OBR-4 `00002` Manual Count): blood type, morphology and the
  manual differential a technician enters on the analyzer.
- The asterisk values the QC example shows (`***.**`, `**.*`, `****`, `.***`): the manual prints
  them without defining them, so they are not declared as run failures and OpenELIS holds them.
- The patient's age, the remark and the histogram images as parts of the bundle.
- The worklist inquiry (ORM^O01, C.3.3): the profile is results only.
