# genexpert-astm, revision 8: evidence

Every code, value and record the profile declares, with the document and section that defines
it. A row with no vendor document is listed under "Not verified" and is not in the profile.
Revision 8 is `src/main/resources/analyzer-profiles/genexpert-astm-v8.json` (contract 2.0).

## Documents

| Short name | Document                                                                                                         | Where                                                                                                                                   |
| ---------- | ---------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------- |
| 301-2002   | Cepheid GeneXpert System Software LIS Interface Protocol Specification, 301-2002 Rev. E, December 2014           | `openelis-work/assets/vendor-manuals/genexpert-lis-protocol-spec.pdf`                                                                   |
| 302-2261   | Cepheid GeneXpert LIS Interface Protocol Specification, 302-2261 Rev. C, September 2020                          | https://infomine.cepheid.com/sites/default/files/2021-10/LIS%20Protocol%20Specification%20302-2261%2C%20Rev.%20C.pdf                    |
| 303-0251   | Cepheid Xpert HIV-1 Viral Load XC, Laboratory Information System Guidance, 303-0251 Rev. A, February 2023        | https://infomine.cepheid.com/sites/default/files/2023-05/303-0251%20Rev.%20A%20LIS%20Guidance%20Xpert%20HIV-1%20VL%20XC%20v3.pdf        |
| 302-7279   | Cepheid Xpert Xpress CoV-2/Flu/RSV plus, Laboratory Information System Guidance, 302-7279 Rev. A, September 2021 | https://infomine.cepheid.com/sites/default/files/2021-10/LIS%20Guidance%20Bulletin%20CoV-2%20Flu%20RSV%20plus%20302-7279%20Rev.%20A.pdf |

The example messages in 303-0251 section 2.1.1 and 302-7279 section 6 are in
`src/test/resources/cepheid-examples/`; `GeneXpertBaselineProfileTest` replays all of them.

## Tests

The codes are Cepheid's suggested host test and result codes. They are user-defined on each
instrument (303-0251 section 1, 302-7279 section 2), so a connection may override any of them.

| Profile test | Assay                                                  | Code defined in                                                        | LOINC                                                             | Result type               |
| ------------ | ------------------------------------------------------ | ---------------------------------------------------------------------- | ----------------------------------------------------------------- | ------------------------- |
| `HIVVL`      | Xpert HIV-1 Viral Load XC, ADF version 3               | 303-0251 section 1 (host test code `HIVVL`)                            | 20447-9 (303-0251 does not give one; LOINC database)              | quantitative, with a call |
| `SARSCOV2`   | Xpress SARS-CoV-2_Flu_RSV plus and SARS-CoV-2_Flu plus | 302-7279 section 2 (result code `SARSCOV2`)                            | 94500-6 (302-7279 section 3 gives it for the single-result assay) | qualitative               |
| `FLUA`       | the same two panels                                    | 302-7279 section 2 (result code `FLUA`)                                | 92142-9 (LOINC database)                                          | qualitative               |
| `FLUB`       | the same two panels                                    | 302-7279 section 2 (result code `FLUB`)                                | 92141-1 (LOINC database)                                          | qualitative               |
| `RSV`        | Xpress SARS-CoV-2_Flu_RSV plus                         | 302-7279 section 2 (result code `RSV`)                                 | 92131-2 (LOINC database)                                          | qualitative               |
| `SARSCOV2_3` | Xpress SARS-CoV-2 plus (single result)                 | 302-7279 section 2 (host test code `SARSCOV2_3`, also the upload code) | 94500-6 (302-7279 section 3)                                      | qualitative               |
| `MTB`        | Xpert MTB/RIF, panel `MTBRIF`                          | 302-2261 appendix A (result code `MTB`)                                | 85362-2 (LOINC database)                                          | text                      |
| `RIF`        | Xpert MTB/RIF, panel `MTBRIF`                          | 302-2261 appendix A (result code `RIF`)                                | 89372-7 (LOINC database)                                          | text                      |

LOINC codes were checked against the NLM LOINC service on 2026-10-06. A vendor does not own a
LOINC code; where the vendor document gives none, the code is the standard one for the analyte.

The two panel assays send the same result codes under their own assay names (302-7279 section
6), so the profile names the three-analyte assay; the assay name each record carries reaches
OpenELIS as the instrument sent it.

## Values

| Value                                                     | Where it is defined                                                | Standard codings                                                                                                                                             |
| --------------------------------------------------------- | ------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `DETECTED`, `NOT DETECTED`, `INVALID` (HIV-1 main result) | 303-0251 section 1, 2.1.1                                          | `DETECTED` LOINC LA11882-0, SNOMED 260373001, CIEL 1301; `NOT DETECTED` LOINC LA11883-8, SNOMED 260415000, CIEL 1302; `INVALID` LOINC LA15841-2, CIEL 163611 |
| `POSITIVE`, `NEGATIVE`, `INVALID` (panel main results)    | 302-7279 section 7                                                 | `POSITIVE` LOINC LA6576-8, SNOMED 10828004, CIEL 703; `NEGATIVE` LOINC LA6577-6, SNOMED 260385009, CIEL 664; `INVALID` as above                              |
| `POS`, `NEG`, `INVALID` (analyte calls)                   | 303-0251 2.1.1, 302-7279 section 6                                 | as `POSITIVE`, `NEGATIVE`, `INVALID`                                                                                                                         |
| `PASS`, `FAIL`, `NA` (internal controls)                  | 303-0251 2.1.1 (`PASS`, `FAIL`), 302-7279 section 6 (`PASS`, `NA`) | none cited                                                                                                                                                   |
| `ERROR`, `NO RESULT`                                      | 303-0251 section 1, 302-7279 section 7                             | run failures, not answers                                                                                                                                    |
| `NO RESULT - REPEAT TEST`                                 | 302-7279 section 6.1.1.9                                           | run failure                                                                                                                                                  |

The LOINC and SNOMED codings were read from the LOINC answer lists LL744-4 and LL2021-5 through
the NLM LOINC service and the CIEL release of 28 April 2026 (2026-10-05). `ERROR` and `NO RESULT`
are held as failed runs (rule 10); `INVALID` is a coded answer.

## Translations

303-0251 section 3 and 302-7279 section 7 give each value in English, French, German, Spanish,
Italian, Portuguese, Russian, Ukrainian and Japanese. The profile lists every translation that
differs from the English and belongs to one value. Left out because Cepheid gives it to two values:
the Ukrainian `НЕДІЙСНИЙ`, which the 302-7279 table prints for both Error and Invalid. The analyte
calls `POS` and `NEG` are not in either table, so they have no translations.

## Where each part sits

Cepheid defines these in 301-2002 section 6.3.4.1.6, "Parsing a result record", and sections
6.3.4.1.2 to 6.3.4.1.7 for the other records.

| Part                      | Field         | Defined as                                                                                                     |
| ------------------------- | ------------- | -------------------------------------------------------------------------------------------------------------- |
| test code                 | R.3.4         | assay host test code (single result) or result test code (multi-result)                                        |
| assay name, version       | R.3.5, R.3.6  | only on the main result                                                                                        |
| analyte                   | R.3.7         | analyte or complementary result; on a main result of a multi-result test it is the result name and is not read |
| complementary name        | R.3.8         | `Ct`, `EndPt`, `Delta Ct`, `LOG`                                                                               |
| call, number              | R.4.1, R.4.2  | qualitative and quantitative result                                                                            |
| unit, range, flag         | R.5, R.6, R.7 | `<` and `>` are off scale; the range is "lower to upper"                                                       |
| status                    | R.9           | `F`, `I`, `X`, `C`                                                                                             |
| operator                  | R.11          | `<None>` means none                                                                                            |
| started, completed        | R.12, R.13    | ASTM date and time                                                                                             |
| instrument identification | R.14          | computer, instrument S/N, module S/N, cartridge S/N, reagent lot, expiry                                       |
| note                      | C.4           | `Notes` or `Error`, code, description, details, time (section 6.3.4.1.7)                                       |
| patient identifier, name  | P.5, P.6      | section 6.3.4.1.3                                                                                              |
| specimen descriptor       | O.16          | section 6.3.4.1.4                                                                                              |

A record is identified by its code and its sub-identity: the analyte and complementary names in
the vendor's sub-ID notation (`HIV-1&Ct`, `&LOG`, empty for the main result). An off-scale call
(`DETECTED` with flag `<` and range `40.00 to 10000000.00`) arrives as a quantity of the limit
with its comparator (rules 11 and 17).

## Discrepancies in the vendor documents

- 301-2002 section 6.3.4.1 says analyte results, Ct, EndPt and Delta Ct are not uploaded. The 2021
  and 2023 guidance shows them in the ASTM upload. The profile declares them; an instrument that
  does not send them sends nothing for those rows.
- 302-7279 suggests host test codes `SARSCOV2FLURSV`, `SARSCOV2FLU` and `SARSCOV2_3` but its
  examples use `COVFLURSVPLUS`, `COVFLUPLUS` and `COVPLUS`. Result codes agree. The tests replay the
  single-result examples with the code `COVPLUS` set as a connection override.
- 302-7279 prints the single-result assay's Error and Invalid examples (6.3.1.3 and 6.3.1.4)
  under the two-assay panel's code, so the single-result assay's own Error and Invalid messages
  are not shown.

## Not verified, and not in the profile

- The value strings of Xpert MTB/RIF and MTB/RIF Ultra (MTB DETECTED HIGH, MEDIUM, LOW, VERY LOW,
  Trace, RIF Resistance DETECTED, NOT DETECTED, INDETERMINATE are package-insert display text).
  302-2261 appendix A gives the host panel `MTBRIF` and the result codes `MTB`, `INV`, `QC`,
  `RIF` and no values. `MTB` and `RIF` are therefore text results, bound per analyzer. `INV` and
  `QC` are not declared. No Cepheid LIS guidance for MTB/RIF Ultra was found on the vendor
  portal or in `openelis-work`.
- The bacillary level and rifampicin resistance on LOINC 89372-7 as separate results (rule 12),
  for the same reason.
- Which Dx software versions send analyte records.
- The patient identifier field. 301-2002 names P.5 "Patient ID 1, patient identification" and P.3
  "Patient ID 2, practice-assigned"; the profile reads P.5.
- A standard coding for `PASS`, `FAIL` and `NA`.
- HL7 upload messages: this profile is ASTM only.
