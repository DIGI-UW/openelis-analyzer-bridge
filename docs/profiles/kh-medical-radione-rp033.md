# kh-medical-radione-rp033, revision 1: evidence

`src/main/resources/analyzer-profiles/kh-medical-radione-rp033.json` (contract 2.0). The KH Medical
RADIONE (KM010) sends no ASTM or HL7. The operator exports each run as one ZIP, and the lab drops it
in the connection's directory. This profile reads runs of the RADIONE Ebola/Marburg Detection Kit,
RP033 (OGC-1610, DRC Ebola response).

## Documents

| Short name   | Document                                                                                      | Where                                                                                        |
| ------------ | --------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------- |
| IFU          | RADIONE Ebola/Marburg Detection Kit (RP033), instructions for use Rev.03 (WHO EUL submission) | WHO EUL                                                                                      |
| RADIONE spec | KH Medical RADIONE (KM010) Molecular POC Analyzer Integration Spec v0.4, 2026-11-05           | `openelis-work/designs/analyzer-integration/radione-rp033-integration-spec-v0.4.md`          |
| Catalog      | Ebola and Marburg PCR: test catalog metadata, 2026-11-05                                      | `openelis-work/designs/analyzer-integration/radione-rp033-ebola-marburg-catalog-metadata.md` |
| Export       | `RadioOne data Export_676_RP033_2026-10-10-00-22-19_2_0027.zip`, captured at a DRC lab        | OGC-1610 attachment; the spec traces it in section 10                                        |

No vendor interface document exists; the IFU defines the kit and the spec, read from the captured
export, defines the file. The export carries no patient identifier.

## The export

One ZIP per run, holding one folder named `RadioOne data Export_{SampleNo}_{Kit}_{RunTime}_{Serial4}`
(spec 2.1, 4.1):

| Entry               | Read | What it carries                                                                        |
| ------------------- | ---- | -------------------------------------------------------------------------------------- |
| `data.xlsx`         | yes  | sheet `Result`: B1 the instrument serial, row 3 the header, a row per well and channel |
| `PC`                | yes  | JSON run record: `sampleno` is the sample number (spec 4.3)                            |
| `shiyanstatedata`   | no   | a compressed copy of `PC`                                                              |
| `WellA_CH1.png` ... | no   | amplification curves                                                                   |

The result sheet names no sample, so `archive` takes the sample number from `PC` and checks it
against the folder name, and checks the folder's four-digit serial against B1. The pattern names
kit `RP033`, so an export of another kit is held rather than read under this layout (spec 5.6).

| Column     | Read as   | Example         |
| ---------- | --------- | --------------- |
| `Location` | test code | `WellA_CH1`     |
| `Target`   | not read  | `Ebola`, `IC`   |
| `CT`       | not read  | `25.61`, `NA`   |
| `Copies`   | not read  | `NA`            |
| `P/N`      | the call  | `P`, `N`, blank |

`Target` is not the test code because `IC` names both wells' internal controls; the well and
channel in `Location` tell them apart.

## Tests and values

| Declared                                                                            | Defined in                                               |
| ----------------------------------------------------------------------------------- | -------------------------------------------------------- |
| `WellA_CH1` is test `Ebola`, `WellB_CH1` is test `Marburg`                          | IFU kit layout; spec 5.2                                 |
| `WellA_CH4` is the `IC` record of `Ebola`, `WellB_CH4` the `IC` record of `Marburg` | IFU kit layout; spec 5.2                                 |
| test `Ebola`, LOINC 86518-8; test `Marburg`, LOINC 86574-1; both qualitative        | Catalog section 1                                        |
| the CH1 call lands on component `EBOV` or `MARV` (`call_component`)                 | spec 5.4; Catalog section 2                              |
| component `IC`, sub-identity `IC`, on each test                                     | spec 5.4; Catalog section 2                              |
| values `P` and `N`; `P` LOINC LA11882-0 (Detected), `N` LA11883-8 (Not detected)    | spec 4.2 for the values; Catalog section 3 for the codes |
| controls: sample number starting `PC-` or `NTC-`                                    | spec 6.2 (lab convention until a control export is seen) |

The profile sends the four calls and no result: OpenELIS calculates `RESULT` from `EBOV` or `MARV`
and `IC` (spec 5.3, 5.4). Wells C and D, and channels 2 and 3 of wells A and B, have a blank `P/N`
and send nothing. Ct values are not sent yet; the spec's `EBOV_CT`, `MARV_CT` and `IC_CT`
components need a file row to carry two values.

## Record kinds

| Kind                        | Records                                                         | Declared in                |
| --------------------------- | --------------------------------------------------------------- | -------------------------- |
| result for an ordered test  | the CH1 call and the CH4 internal-control call of wells A and B | `default_test_mappings`    |
| value on a control material | every row of a run whose sample number starts `PC-` or `NTC-`   | `controlResultRecognition` |
| warning about the sample    | none                                                            |                            |
| not received                | rows with a blank `P/N`                                         | (no value to read)         |

## Not verified

- The captured export itself: the Bridge's fixture
  (`src/test/resources/radione-examples/`) is a reconstruction from the spec, not the file.
- A positive, an invalid, a PC and an NTC export: only a negative run has been seen.
- How PC and NTC runs are marked. The run record's `types` (`Unknown` on the patient run) may name
  them; until a control export shows it, the profile recognises them by sample-number prefix only.
- Whether software `NATV1.0.17.12`, which the IFU names, changes the layout of the export from
  `NATV1.0.17.6`, which wrote the captured one.
- Whether several runs can be exported in one ZIP, and what the `_2` after the run time means.
