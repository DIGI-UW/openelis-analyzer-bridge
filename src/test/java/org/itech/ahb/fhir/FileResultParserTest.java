package org.itech.ahb.fhir;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.itech.ahb.fhir.FhirBundleBuilder.AnalyzerResult;
import org.itech.ahb.fhir.HL7ResultParser.ParsedResults;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link FileResultParser}.
 *
 * <p>Creates xlsx fixtures programmatically using Apache POI (no external
 * fixture files needed). Validates column mapping, row grouping by accession,
 * and edge cases like missing columns and empty values.
 */
@DisplayName("FileResultParser")
class FileResultParserTest {

    /**
     * Standard column mappings: spreadsheet column name -> semantic field.
     */
    private static final Map<String, String> STANDARD_MAPPINGS = Map.of(
            "Sample Name", "sampleId",
            "Target", "testCode",
            "CT", "result",
            "Units", "units");

    // The REAL Bruker FluoroCycler XT export headers (verified against site
    // captures + a user-reported file), mapped to semantic fields. The parser
    // matches headers exactly (trim only), so these must be the literal export
    // column names.
    private static final Map<String, String> FLUOROCYCLER_MAPPINGS = Map.of(
            "Sample ID", "sampleId",
            "Type", "qcTask",
            "Calc. Conc.", "result",
            "Result", "interpretation",
            "testCode", "testCode");

    // The previous (broken) profile mapping: idealized headers that match no
    // real export, so every column resolved empty and zero results imported.
    private static final Map<String, String> FLUOROCYCLER_OLD_BROKEN_MAPPINGS = Map.of(
            "SampleID", "sampleId",
            "CalcConc", "result",
            "TargetName", "testCode",
            "Interpretation", "interpretation");

    @Nested
    @DisplayName("FluoroCycler XT real export format (profile mapping regression)")
    class FluoroCyclerRealFormat {

        private final String[] realHeaders = {"Sample ID", "Type", "Calc. Conc.", "Result", "testCode"};

        @Test
        @DisplayName("real export headers parse with the corrected profile mapping")
        void realHeadersParseWithCorrectedMapping() {
            InputStream xlsx = buildXlsx("Sheet1", realHeaders, new Object[][] {
                    {"DEV-PAT-001", "Unknown", 1520.5, "Detected", "VL"},
                    {"STD 1E7", "Standard", null, "STD 1E7 control failed", "VL"},
            });

            List<ParsedResults> results = FileResultParser.parse(xlsx, FLUOROCYCLER_MAPPINGS);

            assertNotNull(results);
            assertFalse(results.isEmpty(),
                    "corrected mapping must extract results from the real export format");
            ParsedResults patient = results.stream()
                    .filter(r -> "DEV-PAT-001".equals(r.accessionNumber())).findFirst().orElse(null);
            assertNotNull(patient, "the patient row must be extracted");
            assertEquals("VL", patient.results().get(0).testCode());
        }

        @Test
        @DisplayName("the old idealized mapping extracted NOTHING from the real export (the bug)")
        void oldMappingExtractedNothing() {
            InputStream xlsx = buildXlsx("Sheet1", realHeaders, new Object[][] {
                    {"DEV-PAT-001", "Unknown", 1520.5, "Detected", "VL"},
            });

            List<ParsedResults> results = FileResultParser.parse(xlsx, FLUOROCYCLER_OLD_BROKEN_MAPPINGS);

            // SampleID/CalcConc/TargetName don't exist in the real export
            // ("Sample ID"/"Calc. Conc."/"testCode") — exact matching resolves nothing.
            assertTrue(results == null || results.isEmpty(),
                    "old idealized mapping must extract nothing — proving the profile was broken");
        }
    }

    /**
     * Build an xlsx workbook in memory and return its bytes as an InputStream.
     */
    private InputStream buildXlsx(String sheetName, String[] headers, Object[][] rows) {
        try {
            Workbook workbook = new XSSFWorkbook();
            Sheet sheet = workbook.createSheet(sheetName);

            // Header row
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                headerRow.createCell(i).setCellValue(headers[i]);
            }

            // Data rows
            for (int r = 0; r < rows.length; r++) {
                Row dataRow = sheet.createRow(r + 1);
                for (int c = 0; c < rows[r].length; c++) {
                    Object val = rows[r][c];
                    if (val instanceof String s) {
                        dataRow.createCell(c).setCellValue(s);
                    } else if (val instanceof Number n) {
                        dataRow.createCell(c).setCellValue(n.doubleValue());
                    } else if (val == null) {
                        // leave cell empty
                    }
                }
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            workbook.write(baos);
            workbook.close();
            return new ByteArrayInputStream(baos.toByteArray());
        } catch (Exception e) {
            throw new RuntimeException("Failed to build test xlsx", e);
        }
    }

    @Nested
    @DisplayName("Basic parsing")
    class BasicParsing {

        @Test
        @DisplayName("Should extract correct accession, test code, and result per row")
        void shouldExtractResultsPerRow() {
            InputStream xlsx = buildXlsx("Sheet1",
                    new String[]{"Sample Name", "Target", "CT", "Units"},
                    new Object[][]{
                            {"SAMPLE001", "HIV-VL", "1520.5", "copies/mL"},
                            {"SAMPLE001", "CT", "28.5", "cycles"},
                    });

            List<ParsedResults> results = FileResultParser.parse(xlsx, STANDARD_MAPPINGS);

            assertNotNull(results);
            assertEquals(1, results.size(), "Both rows share same sampleId");

            ParsedResults group = results.get(0);
            assertEquals("SAMPLE001", group.accessionNumber());
            assertEquals(2, group.results().size());

            List<String> codes = group.results().stream()
                    .map(AnalyzerResult::testCode).toList();
            assertTrue(codes.contains("HIV-VL"));
            assertTrue(codes.contains("CT"));
        }

        @Test
        @DisplayName("Should detect numeric vs text values")
        void shouldDetectValueTypes() {
            InputStream xlsx = buildXlsx("Sheet1",
                    new String[]{"Sample Name", "Target", "CT", "Units"},
                    new Object[][]{
                            {"SAMPLE001", "WBC", "7.5", "10*3/uL"},
                            {"SAMPLE001", "INTERP", "Normal", ""},
                    });

            List<ParsedResults> results = FileResultParser.parse(xlsx, STANDARD_MAPPINGS);

            assertNotNull(results);
            ParsedResults group = results.get(0);

            AnalyzerResult numericResult = group.results().stream()
                    .filter(r -> "WBC".equals(r.testCode())).findFirst().orElseThrow();
            assertTrue(numericResult.isNumeric());

            AnalyzerResult textResult = group.results().stream()
                    .filter(r -> "INTERP".equals(r.testCode())).findFirst().orElseThrow();
            assertFalse(textResult.isNumeric());
        }
    }

    @Nested
    @DisplayName("Row grouping")
    class RowGrouping {

        @Test
        @DisplayName("Multiple rows with same sampleId grouped under same ParsedResults")
        void sameIdGrouped() {
            InputStream xlsx = buildXlsx("Sheet1",
                    new String[]{"Sample Name", "Target", "CT", "Units"},
                    new Object[][]{
                            {"SAMPLE001", "TEST-A", "10.0", "mg/dL"},
                            {"SAMPLE001", "TEST-B", "20.0", "mg/dL"},
                            {"SAMPLE001", "TEST-C", "30.0", "mg/dL"},
                    });

            List<ParsedResults> results = FileResultParser.parse(xlsx, STANDARD_MAPPINGS);

            assertNotNull(results);
            assertEquals(1, results.size());
            assertEquals(3, results.get(0).results().size());
        }

        @Test
        @DisplayName("Different sampleIds produce separate ParsedResults")
        void differentIdsProduceSeparateGroups() {
            InputStream xlsx = buildXlsx("Sheet1",
                    new String[]{"Sample Name", "Target", "CT", "Units"},
                    new Object[][]{
                            {"SAMPLE001", "TEST-A", "10.0", "mg/dL"},
                            {"SAMPLE002", "TEST-B", "20.0", "mg/dL"},
                    });

            List<ParsedResults> results = FileResultParser.parse(xlsx, STANDARD_MAPPINGS);

            assertNotNull(results);
            assertEquals(2, results.size());
        }
    }

    @Nested
    @DisplayName("Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("Missing sampleId column -> row skipped")
        void missingSampleIdSkipped() {
            InputStream xlsx = buildXlsx("Sheet1",
                    new String[]{"Sample Name", "Target", "CT", "Units"},
                    new Object[][]{
                            {null, "TEST-A", "10.0", "mg/dL"},
                            {"", "TEST-B", "20.0", "mg/dL"},
                            {"SAMPLE001", "TEST-C", "30.0", "mg/dL"},
                    });

            List<ParsedResults> results = FileResultParser.parse(xlsx, STANDARD_MAPPINGS);

            assertNotNull(results);
            assertEquals(1, results.size());
            assertEquals("SAMPLE001", results.get(0).accessionNumber());
            assertEquals(1, results.get(0).results().size());
        }

        @Test
        @DisplayName("Empty result -> row skipped")
        void emptyResultSkipped() {
            InputStream xlsx = buildXlsx("Sheet1",
                    new String[]{"Sample Name", "Target", "CT", "Units"},
                    new Object[][]{
                            {"SAMPLE001", "TEST-A", "", "mg/dL"},
                            {"SAMPLE001", "TEST-B", null, "mg/dL"},
                            {"SAMPLE001", "TEST-C", "30.0", "mg/dL"},
                    });

            List<ParsedResults> results = FileResultParser.parse(xlsx, STANDARD_MAPPINGS);

            assertNotNull(results);
            assertEquals(1, results.size());
            assertEquals(1, results.get(0).results().size());
            assertEquals("TEST-C", results.get(0).results().get(0).testCode());
        }

        @Test
        @DisplayName("Missing test code -> row skipped")
        void missingTestCodeSkipped() {
            InputStream xlsx = buildXlsx("Sheet1",
                    new String[]{"Sample Name", "Target", "CT", "Units"},
                    new Object[][]{
                            {"SAMPLE001", "", "10.0", "mg/dL"},
                            {"SAMPLE001", "TEST-B", "20.0", "mg/dL"},
                    });

            List<ParsedResults> results = FileResultParser.parse(xlsx, STANDARD_MAPPINGS);

            assertNotNull(results);
            assertEquals(1, results.get(0).results().size());
            assertEquals("TEST-B", results.get(0).results().get(0).testCode());
        }

        @Test
        @DisplayName("Null input stream -> returns null")
        void nullInputStreamReturnsNull() {
            assertNull(FileResultParser.parse(null, STANDARD_MAPPINGS));
        }

        @Test
        @DisplayName("Null column mappings -> returns null")
        void nullMappingsReturnsNull() {
            InputStream xlsx = buildXlsx("Sheet1",
                    new String[]{"Sample Name", "Target", "CT"},
                    new Object[][]{{"SAMPLE001", "TEST", "10.0"}});

            assertNull(FileResultParser.parse(xlsx, null));
        }

        @Test
        @DisplayName("Empty column mappings -> returns null")
        void emptyMappingsReturnsNull() {
            InputStream xlsx = buildXlsx("Sheet1",
                    new String[]{"Sample Name", "Target", "CT"},
                    new Object[][]{{"SAMPLE001", "TEST", "10.0"}});

            assertNull(FileResultParser.parse(xlsx, Map.of()));
        }

        @Test
        @DisplayName("Column mapping keys not found in header -> no results")
        void unmatchedMappingsReturnNull() {
            InputStream xlsx = buildXlsx("Sheet1",
                    new String[]{"Col A", "Col B", "Col C"},
                    new Object[][]{{"SAMPLE001", "TEST", "10.0"}});

            List<ParsedResults> results = FileResultParser.parse(xlsx, STANDARD_MAPPINGS);

            assertNull(results, "No columns matched, so no results should be extracted");
        }

        @Test
        @DisplayName("All rows empty -> returns null")
        void allRowsEmptyReturnsNull() {
            InputStream xlsx = buildXlsx("Sheet1",
                    new String[]{"Sample Name", "Target", "CT", "Units"},
                    new Object[][]{
                            {null, null, null, null},
                            {"", "", "", ""},
                    });

            List<ParsedResults> results = FileResultParser.parse(xlsx, STANDARD_MAPPINGS);

            assertNull(results);
        }
    }

    @Nested
    @DisplayName("Sheet resolution")
    class SheetResolution {

        @Test
        @DisplayName("Prefers sheet named 'Results' when present")
        void prefersResultsSheet() {
            // Build workbook with two sheets: "Other" (wrong data) and "Results" (correct data)
            try {
                Workbook workbook = new XSSFWorkbook();

                Sheet other = workbook.createSheet("Other");
                Row otherHeader = other.createRow(0);
                otherHeader.createCell(0).setCellValue("Sample Name");
                otherHeader.createCell(1).setCellValue("Target");
                otherHeader.createCell(2).setCellValue("CT");
                Row otherData = other.createRow(1);
                otherData.createCell(0).setCellValue("WRONG_SAMPLE");
                otherData.createCell(1).setCellValue("WRONG_TEST");
                otherData.createCell(2).setCellValue("999");

                Sheet results = workbook.createSheet("Results");
                Row resultsHeader = results.createRow(0);
                resultsHeader.createCell(0).setCellValue("Sample Name");
                resultsHeader.createCell(1).setCellValue("Target");
                resultsHeader.createCell(2).setCellValue("CT");
                Row resultsData = results.createRow(1);
                resultsData.createCell(0).setCellValue("CORRECT_SAMPLE");
                resultsData.createCell(1).setCellValue("CORRECT_TEST");
                resultsData.createCell(2).setCellValue("42.0");

                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                workbook.write(baos);
                workbook.close();

                InputStream is = new ByteArrayInputStream(baos.toByteArray());
                Map<String, String> mappings = Map.of(
                        "Sample Name", "sampleId",
                        "Target", "testCode",
                        "CT", "result");

                List<ParsedResults> parsed = FileResultParser.parse(is, mappings);

                assertNotNull(parsed);
                assertEquals(1, parsed.size());
                assertEquals("CORRECT_SAMPLE", parsed.get(0).accessionNumber());

            } catch (Exception e) {
                fail("Test setup failed: " + e.getMessage());
            }
        }
    }

    @Nested
    @DisplayName("Interpretation fallback")
    class InterpretationFallback {

        @Test
        @DisplayName("Uses interpretation when result column is empty")
        void usesInterpretationWhenResultEmpty() {
            InputStream xlsx = buildXlsx("Sheet1",
                    new String[]{"Sample Name", "Target", "CT", "Units", "Interpretation"},
                    new Object[][]{
                            {"SAMPLE001", "TEST-A", "", "mg/dL", "Positive"},
                    });

            Map<String, String> mappings = new HashMap<>(STANDARD_MAPPINGS);
            mappings.put("Interpretation", "interpretation");

            List<ParsedResults> results = FileResultParser.parse(xlsx, mappings);

            assertNotNull(results);
            assertEquals(1, results.size());
            assertEquals("Positive", results.get(0).results().get(0).value());
        }
    }

    @Nested
    @DisplayName("QC classification")
    class QcClassification {

        @Test
        @DisplayName("QuantStudio control accession is flagged as QC")
        void quantStudioControlAccessionFlaggedAsQc() {
            InputStream xlsx = buildXlsx("Results",
                    new String[]{"Well", "Sample Name", "Target Name", "Quantity Mean", "Task"},
                    new Object[][]{
                            {"A1", "CNEG", "VIH-1", "1520.5", "Control"},
                    });

            Map<String, String> mappings = Map.of(
                    "Sample Name", "sampleId",
                    "Target Name", "testCode",
                    "Quantity Mean", "result",
                    "Task", "qcTask");

            List<ParsedResults> results = FileResultParser.parse(xlsx, mappings);

            assertNotNull(results);
            assertEquals(1, results.size());
            assertEquals("CNEG", results.get(0).accessionNumber());
            assertTrue(results.get(0).results().get(0).isControl(),
                    "QuantStudio control accession should be flagged as QC");
        }

        @Test
        @DisplayName("QuantStudio patient accession remains non-QC")
        void quantStudioPatientAccessionRemainsNonQc() {
            InputStream xlsx = buildXlsx("Results",
                    new String[]{"Well", "Sample Name", "Target Name", "Quantity Mean", "Task"},
                    new Object[][]{
                            {"A1", "HARN-QS7-2026-00001", "VIH-1", "1520.5", "UNKNOWN"},
                    });

            Map<String, String> mappings = Map.of(
                    "Sample Name", "sampleId",
                    "Target Name", "testCode",
                    "Quantity Mean", "result",
                    "Task", "qcTask");

            List<ParsedResults> results = FileResultParser.parse(xlsx, mappings);

            assertNotNull(results);
            assertEquals(1, results.size());
            assertFalse(results.get(0).results().get(0).isControl(),
                    "Patient accession should not be flagged as QC");
        }

        @Test
        @DisplayName("SPECIMEN_ID_PREFIX rule -> isControl=true AND controlLevel=operand")
        void specimenIdPrefixRuleSetsControlLevel() {
            InputStream xlsx = buildXlsx("Results",
                    new String[]{"Well", "Sample Name", "Target Name", "Quantity Mean"},
                    new Object[][]{
                            {"A1", "LPC-2026-Q2", "VIH-1", "32.5"},
                    });

            Map<String, String> mappings = Map.of(
                    "Sample Name", "sampleId",
                    "Target Name", "testCode",
                    "Quantity Mean", "result");

            List<org.itech.ahb.qc.QcRule> rules = List.of(
                    new org.itech.ahb.qc.QcRule(
                            "SPECIMEN_ID_PREFIX", null, "LPC"));

            List<ParsedResults> results = FileResultParser.parse(xlsx, mappings, null, rules);

            assertNotNull(results);
            var ar = results.get(0).results().get(0);
            assertTrue(ar.isControl(), "Row should be flagged as control");
            assertEquals("LPC", ar.controlLevel(),
                    "Operand of SPECIMEN_ID_PREFIX rule should propagate as controlLevel");
        }

        @Test
        @DisplayName("FIELD_EQUALS rule -> isControl=true but controlLevel stays null (operand is a predicate, not a level)")
        void fieldEqualsRuleDoesNotPopulateControlLevel() {
            InputStream xlsx = buildXlsx("Results",
                    new String[]{"Well", "Sample Name", "Target Name", "Quantity Mean", "Task"},
                    new Object[][]{
                            {"A1", "RANDOM-001", "VIH-1", "32.5", "STANDARD"},
                    });

            Map<String, String> mappings = Map.of(
                    "Sample Name", "sampleId",
                    "Target Name", "testCode",
                    "Quantity Mean", "result",
                    "Task", "qcTask");

            List<org.itech.ahb.qc.QcRule> rules = List.of(
                    new org.itech.ahb.qc.QcRule(
                            "FIELD_EQUALS", "QC_TASK", "STANDARD"));

            List<ParsedResults> results = FileResultParser.parse(xlsx, mappings, null, rules);

            assertNotNull(results);
            var ar = results.get(0).results().get(0);
            assertTrue(ar.isControl(),
                    "FIELD_EQUALS QC_TASK=STANDARD match should still mark row as control");
            assertNull(ar.controlLevel(),
                    "FIELD_EQUALS operand is a predicate value (e.g. 'STANDARD'), "
                    + "not a control level — must not propagate as controlLevel");
        }
    }

    // ── CSV Parsing Tests ───────────────────────────────────────────

    @Nested
    @DisplayName("CSV parsing (parseCsv)")
    class CsvParsing {

        private static final Map<String, String> CSV_MAPPINGS = Map.of(
                "Sample Number", "sampleId",
                "Test Name", "testCode",
                "Result", "result",
                "Unit", "units");

        @Test
        @DisplayName("Flat CSV with headers → extracts results")
        void flatCsvExtractsResults() {
            String csv = "Sample Number,Test Name,Result,Unit\n"
                    + "S001,TSH,3.45,mIU/L\n"
                    + "S002,TSH,0.57,mIU/L\n";

            List<ParsedResults> results = FileResultParser.parseCsv(
                    csv.getBytes(), CSV_MAPPINGS, ",", 0);

            assertNotNull(results);
            assertEquals(2, results.size());
        }

        @Test
        @DisplayName("skipRows skips metadata lines before header")
        void skipRowsSkipsMetadata() {
            String csv = "Finecare FIA Meter III Plus,FS-205,SN001\n"
                    + "Sample Number,Test Name,Result,Unit\n"
                    + "S001,TSH,3.45,mIU/L\n";

            List<ParsedResults> results = FileResultParser.parseCsv(
                    csv.getBytes(), CSV_MAPPINGS, ",", 1);

            assertNotNull(results);
            assertEquals(1, results.size());
            assertEquals("S001", results.get(0).accessionNumber());
        }

        @Test
        @DisplayName("Semicolon delimiter (French locale) → parses correctly")
        void semicolonDelimiterParses() {
            String csv = "Sample Number;Test Name;Result;Unit\n"
                    + "S001;HIV ELISA;2.345;OD\n"
                    + "S002;HIV ELISA;0.048;OD\n";

            List<ParsedResults> results = FileResultParser.parseCsv(
                    csv.getBytes(), CSV_MAPPINGS, ";", 0);

            assertNotNull(results);
            assertEquals(2, results.size());
            assertEquals("2.345", results.get(0).results().get(0).value());
        }

        @Test
        @DisplayName("Comparison operators (<2, >100) preserved as string")
        void comparisonOperatorsPreserved() {
            String csv = "Sample Number,Test Name,Result,Unit\n"
                    + "S001,TSH,<2,mIU/L\n"
                    + "S002,hCG,>100,mIU/mL\n";

            List<ParsedResults> results = FileResultParser.parseCsv(
                    csv.getBytes(), CSV_MAPPINGS, ",", 0);

            assertNotNull(results);
            ParsedResults s1 = results.stream()
                    .filter(r -> "S001".equals(r.accessionNumber())).findFirst().orElseThrow();
            // <2 is treated as text (not numeric) — isNumericValue() returns false for comparison operators
            assertEquals("<2", s1.results().get(0).value());

            ParsedResults s2 = results.stream()
                    .filter(r -> "S002".equals(r.accessionNumber())).findFirst().orElseThrow();
            assertEquals(">100", s2.results().get(0).value());
        }

        @Test
        @DisplayName("BOM at start of file is stripped")
        void bomStripped() {
            String csv = "\uFEFFSample Number,Test Name,Result,Unit\n"
                    + "S001,TSH,3.45,mIU/L\n";

            List<ParsedResults> results = FileResultParser.parseCsv(
                    csv.getBytes(), CSV_MAPPINGS, ",", 0);

            assertNotNull(results);
            assertEquals(1, results.size());
        }

        @Test
        @DisplayName("Empty content → returns null")
        void emptyContentReturnsNull() {
            assertNull(FileResultParser.parseCsv(new byte[0], CSV_MAPPINGS, ",", 0));
        }

        @Test
        @DisplayName("QuantStudio multi-section CSV → picks the LAST 'Well' header (Results section)")
        void quantStudioMultiSectionCsvPicksLastWellHeader() {
            // QuantStudio Design & Analysis CSV exports include two sections
            // both starting with a row whose first cell is "Well": [Sample Setup]
            // (assigns Well -> sample) then [Results] (carries Cq/quantities).
            // We want the LAST "Well" row picked so the Results section is parsed,
            // not the Sample Setup section.
            String csv = "* Block Type = 96-Well Block (0.2mL)\n"
                    + "* Chemistry = TAQMAN\n"
                    + "[Sample Setup]\n"
                    + "Well,Sample Name,Target Name\n"
                    + "A1,IGNORE-ME,VIH-1\n"
                    + "[Results]\n"
                    + "Well,Sample Name,Target Name,CT\n"
                    + "A1,RESULT-001,VIH-1,28.5\n";

            Map<String, String> mappings = Map.of(
                    "Sample Name", "sampleId",
                    "Target Name", "testCode",
                    "CT", "ctValue");

            List<ParsedResults> results = FileResultParser.parseCsv(
                    csv.getBytes(), mappings, ",", 0);

            assertNotNull(results);
            // Setup section's "IGNORE-ME" must NOT show up — only Results section.
            assertEquals(1, results.size(), "Should parse only the Results section");
            assertEquals("RESULT-001", results.get(0).accessionNumber());
        }

        @Test
        @DisplayName("Empty result column → falls back to ctValue (QuantStudio CT fallback chain)")
        void emptyResultFallsBackToCtValue() {
            // Real QuantStudio rows have CT but blank result cells when only Cq
            // matters. The parser's value fallback (result -> ctValue ->
            // interpretation) must keep the row and forward the CT value.
            String csv = "Sample Name,Target Name,Result,CT\n"
                    + "S001,VIH-1,,28.5\n";

            Map<String, String> mappings = Map.of(
                    "Sample Name", "sampleId",
                    "Target Name", "testCode",
                    "Result", "result",
                    "CT", "ctValue");

            List<ParsedResults> results = FileResultParser.parseCsv(
                    csv.getBytes(), mappings, ",", 0);

            assertNotNull(results);
            assertEquals(1, results.size(), "Row with blank result + CT must be retained");
            assertEquals("28.5", results.get(0).results().get(0).value(),
                    "CT value must be forwarded when result column is blank");
        }

        @Test
        @DisplayName("Null content → returns null")
        void nullContentReturnsNull() {
            assertNull(FileResultParser.parseCsv(null, CSV_MAPPINGS, ",", 0));
        }

        @Test
        @DisplayName("Tab-delimited well-per-row → parses correctly")
        void tabDelimitedParses() {
            Map<String, String> tabMappings = Map.of(
                    "WellPosition", "sampleId",
                    "TestCode", "testCode",
                    "OD_450", "result");

            String tsv = "WellPosition\tTestCode\tOD_450\n"
                    + "A1\tHIV ELISA\t2.345\n"
                    + "A2\tHIV ELISA\t0.048\n";

            List<ParsedResults> results = FileResultParser.parseCsv(
                    tsv.getBytes(), tabMappings, "\t", 0);

            assertNotNull(results);
            assertEquals(2, results.size());
        }

        @Test
        @DisplayName("Wondfo-style 40-column CSV with skipRows")
        void wondfoStyleCsv() {
            Map<String, String> wondfoMappings = Map.of(
                    "Serial Number", "deviceSerialNumber",
                    "Sample Number", "sampleId",
                    "Test Name", "testCode",
                    "Result", "result",
                    "Unit", "units");

            String csv = "Finecare FIA,FS-205,SN001,,\n"
                    + "Serial Number,Sample Number,Sample Type,Test Name,Result,Unit\n"
                    + "SN001,10H,Serum,TSH,3.45,mIU/L\n"
                    + "SN002,10J,Serum,TSH,<2,mIU/L\n";

            List<ParsedResults> results = FileResultParser.parseCsv(
                    csv.getBytes(), wondfoMappings, ",", 1);

            assertNotNull(results);
            assertEquals(2, results.size());

            ParsedResults first = results.stream()
                    .filter(r -> "10H".equals(r.accessionNumber())).findFirst().orElseThrow();
            assertEquals("3.45", first.results().get(0).value());
            assertEquals("TSH", first.results().get(0).testCode());
        }

        @Test
        @DisplayName("CSV alias mappings continue when first alias is missing")
        void csvAliasFallbackWhenFirstColumnMissing() {
            Map<String, String> aliasMappings = new java.util.LinkedHashMap<>();
            aliasMappings.put("Sample ID", "sampleId"); // Missing in CSV header
            aliasMappings.put("Sample Number", "sampleId"); // Present in CSV header
            aliasMappings.put("Test Name", "testCode");
            aliasMappings.put("Result", "result");

            String csv = "Serial Number;Sample Number;Test Name;Result\n"
                    + "SN001;DEV01265000000000101;TSH;3.45\n";

            List<ParsedResults> results = FileResultParser.parseCsv(
                    csv.getBytes(), aliasMappings, ";", 0);

            assertNotNull(results);
            assertEquals(1, results.size());
            assertEquals("DEV01265000000000101", results.get(0).accessionNumber());
            assertEquals("TSH", results.get(0).results().get(0).testCode());
            assertEquals("3.45", results.get(0).results().get(0).value());
        }

        @Test
        @DisplayName("Tecan-style CSV parses even when testCode mapping is absent")
        void tecanStyleCsvWithoutTestCodeMapping() {
            Map<String, String> tecanMappings = Map.of(
                    "SampleID", "sampleId",
                    "OD_450", "result");

            String csv = "WellPosition;SampleID;OD_450;TestCode\n"
                    + "A1;DEV01265100000000101;2.345;HIV ELISA\n";

            List<ParsedResults> results = FileResultParser.parseCsv(
                    csv.getBytes(), tecanMappings, ";", 0);

            assertNotNull(results);
            assertEquals(1, results.size());
            assertEquals("DEV01265100000000101", results.get(0).accessionNumber());
            assertEquals("HIV ELISA", results.get(0).results().get(0).testCode());
            assertEquals("2.345", results.get(0).results().get(0).value());
        }

        @Test
        @DisplayName("Multiskan-style CSV parses even when sample/test mappings are absent")
        void multiskanStyleCsvWithoutSampleAndTestMappings() {
            Map<String, String> multiskanMappings = Map.of(
                    "Abs", "result");

            String csv = "WellPosition;SampleID;Abs;TestCode\n"
                    + "A1;DEV01265200000000101;2.345;HIV ELISA\n";

            List<ParsedResults> results = FileResultParser.parseCsv(
                    csv.getBytes(), multiskanMappings, ";", 0);

            assertNotNull(results);
            assertEquals(1, results.size());
            assertEquals("DEV01265200000000101", results.get(0).accessionNumber());
            assertEquals("HIV ELISA", results.get(0).results().get(0).testCode());
            assertEquals("2.345", results.get(0).results().get(0).value());
        }

        @Test
        @DisplayName("ELISA control prefixes (NEG, POS, NC, PC, Blanc) flagged as controls")
        void elisaControlPrefixesFlagged() {
            Map<String, String> mappings = Map.of(
                    "SampleID", "sampleId",
                    "TestCode", "testCode",
                    "Result", "result");

            String csv = "SampleID,TestCode,Result\n"
                    + "DEV01265100000000001,HIV ELISA,2.345\n"
                    + "NEG,HIV ELISA,0.045\n"
                    + "POS,HIV ELISA,2.401\n"
                    + "NC,HIV ELISA,0.038\n"
                    + "PC,HIV ELISA,2.510\n"
                    + "Blanc,HIV ELISA,0.012\n"
                    + "BLANK,HIV ELISA,0.008\n";

            List<ParsedResults> results = FileResultParser.parseCsv(
                    csv.getBytes(), mappings, ",", 0);

            assertNotNull(results);

            // Patient sample should NOT be control
            ParsedResults patient = results.stream()
                    .filter(r -> r.accessionNumber().startsWith("DEV")).findFirst().orElseThrow();
            assertFalse(patient.results().get(0).isControl(), "Patient sample should not be control");

            // All QC rows should be flagged as controls
            for (String controlId : List.of("NEG", "POS", "NC", "PC", "Blanc", "BLANK")) {
                ParsedResults qc = results.stream()
                        .filter(r -> r.accessionNumber().equalsIgnoreCase(controlId)).findFirst()
                        .orElseThrow(() -> new AssertionError("Missing QC row: " + controlId));
                assertTrue(qc.results().get(0).isControl(),
                        controlId + " should be flagged as control");
            }
        }
    }

    /**
     * Madagascar real-file integration tests.
     *
     * <p>Drives {@link FileResultParser} against synthetic fixtures that
     * mirror the real QuantStudio SDS export shape (metadata preamble +
     * header-scan requirement) and the real Fluorocycler-XT shape. Real-file
     * tests were removed — they depended on gitignored fixtures from Herbert
     * Yiga's UAT mount ({@code docs/debug-local/...}) that can't be checked
     * into a public repo. Those tests silently passed on CI and any fresh
     * checkout without the fixtures, which Samuel flagged as false positives.
     * Synthetic fixtures reproduce the structural quirks the parser must
     * handle without including patient data.
     *
     * <p>Plan reference: {@code ~/.claude/plans/mellow-honking-cascade.md},
     * Phase A and A.3. Each failure here maps to a specific fix in the plan.
     */
    @Nested
    @DisplayName("Madagascar-shape synthetic fixtures")
    class MadagascarRealFiles {

        /**
         * Column mapping from
         * {@code projects/analyzer-profiles/file/quantstudio.json} (version
         * 1.2.0). Kept in sync with the committed profile — if the profile
         * is updated, update this constant too so the test continues to
         * reflect what production actually uses.
         */
        private static final Map<String, String> QUANTSTUDIO_COLUMN_MAPPING = Map.of(
                "Sample Name", "sampleId",
                "Target Name", "testCode",
                "Quantity Mean", "result",
                "CT", "ctValue",
                "Well Position", "position",
                "Task", "qcTask",
                "Quantity", "quantityRaw",
                "Ct Mean", "ctMean",
                "Comments", "comments");

        @Test
        @DisplayName("Synthetic Fluorocycler-shape file + perFileTestCode → CI baseline for A.3.3")
        void syntheticFluorocyclerShape_withPerFileTestCode_extractsRows() throws Exception {
            // Build a synthetic Fluorocycler-shape xlsx: 1 sheet, headers at
            // row 0, columns Row / Col / Sample ID / Type / Result. NO
            // TargetName column. This mirrors what's actually on Herbert's
            // /mnt for both HIV-result.xlsx and ARBOVIROSE.xlsx.
            Workbook wb = new XSSFWorkbook();
            Sheet sheet = wb.createSheet("Sheet1");

            String[] cols = {"Row", "Col", "Sample ID", "Type", "Result"};
            Row header = sheet.createRow(0);
            for (int i = 0; i < cols.length; i++) {
                header.createCell(i).setCellValue(cols[i]);
            }

            Object[][] data = {
                    {"A", "1", "DEV01263000000000001", "Unknown",  "HIV-1 + (CP=38.0)"},
                    {"A", "2", "DEV01263000000000002", "Unknown",  "Negative -Not interpretable"},
                    {"A", "3", "DEV01263000000000003", "Standard", "STD 1E7"},
                    {"A", "4", "DEV01263000000000004", "Positive", "Positive Control invalid"},
                    {"A", "5", "DEV01263000000000005", "Negative", "Negative Control valid"},
            };
            for (int r = 0; r < data.length; r++) {
                Row dataRow = sheet.createRow(r + 1);
                for (int c = 0; c < data[r].length; c++) {
                    dataRow.createCell(c).setCellValue((String) data[r][c]);
                }
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            wb.write(baos);
            wb.close();

            Map<String, String> mapping = Map.of(
                    "Sample ID", "sampleId",
                    "Type", "qcTask",
                    "Result", "result");

            List<ParsedResults> results = FileResultParser.parse(
                    new ByteArrayInputStream(baos.toByteArray()), mapping, "VIH-1");

            assertNotNull(results);
            assertFalse(results.isEmpty(), "Parser extracted zero results from synthetic Fluorocycler-shape fixture");

            // All 5 rows should come through — clinical (Unknown) + control rows
            // marked isControl via the Type column.
            assertEquals(5, results.size(),
                    "Expected one ParsedResults per sample (5 total); got " + results.size());

            // Clinical Unknown rows are not marked as controls
            ParsedResults clinical = results.stream()
                    .filter(pr -> pr.accessionNumber().equals("DEV01263000000000001"))
                    .findFirst().orElseThrow();
            assertFalse(clinical.results().get(0).isControl(),
                    "Unknown sample should not be flagged as control");
            assertEquals("VIH-1", clinical.results().get(0).testCode(),
                    "Clinical row should use fallback testCode from perFileTestCode");

            // Standard row IS a control now (thanks to isControlRow extension in eae654f)
            ParsedResults standard = results.stream()
                    .filter(pr -> pr.accessionNumber().equals("DEV01263000000000003"))
                    .findFirst().orElseThrow();
            assertTrue(standard.results().get(0).isControl(),
                    "Standard sample should be flagged as control (Type=Standard)");

            // Positive/Negative control rows should also be flagged
            ParsedResults posCtrl = results.stream()
                    .filter(pr -> pr.accessionNumber().equals("DEV01263000000000004"))
                    .findFirst().orElseThrow();
            assertTrue(posCtrl.results().get(0).isControl(),
                    "Positive control row should be flagged (Type=Positive → control via extended isControlRow)");
        }

        // ------------------------------------------------------------------
        // Synthetic QS-shaped fixture — CI-safe baseline
        // ------------------------------------------------------------------

        /**
         * Builds a .xls workbook that mirrors the real QuantStudio 5
         * Arbo-extraitQS5.xls shape: three sheets (Sample Setup, Amplification
         * Data, Results), the Results sheet has a 20-row metadata preamble
         * starting with "Block Type" / "Chemistry" / etc., followed by a
         * "Well"-headed data table, followed by a mix of Task=UNKNOWN clinical
         * rows and Task=STANDARD calibration rows.
         */
        private InputStream buildQuantStudioLikeWorkbook(int headerRowOffset) {
            try {
                Workbook wb = new HSSFWorkbook();
                wb.createSheet("Sample Setup");
                wb.createSheet("Amplification Data");
                Sheet results = wb.createSheet("Results");

                String[] metadataKeys = {
                        "Block Type", "Chemistry", "Date Created", "Experiment Barcode",
                        "Experiment Comment", "Experiment File Name", "Experiment Name",
                        "Experiment Run End Time", "Experiment Type", "Instrument Name",
                        "Instrument Serial Number", "Instrument Type", "Passive Reference",
                        "Post-read Stage/Step", "Pre-read Stage/Step",
                        "Quantification Cycle Method", "Signal Smoothing On",
                        "Stage/ Cycle where Analysis is performed", "User Name",
                        "Analysis Type"
                };
                for (int i = 0; i < Math.min(metadataKeys.length, headerRowOffset); i++) {
                    Row r = results.createRow(i);
                    r.createCell(0).setCellValue(metadataKeys[i]);
                    r.createCell(1).setCellValue("<metadata-value>");
                }

                // Header row at the configured offset
                Row header = results.createRow(headerRowOffset);
                String[] cols = {
                        "Well", "Well Position", "Omit", "Sample Name", "Target Name",
                        "Task", "Reporter", "Quencher", "CT", "Ct Mean", "Ct SD",
                        "Quantity", "Quantity Mean", "Quantity SD", "Y-Intercept"
                };
                for (int c = 0; c < cols.length; c++) {
                    header.createCell(c).setCellValue(cols[c]);
                }

                // Data rows — mix of CLINICAL (Task=UNKNOWN) and STANDARD
                Object[][] data = {
                        {"1", "A1", "0", "DEV01262100000000001", "CHIKV", "UNKNOWN", "FAM", "NFQ", "28.34", "28.34", "", "", "1520.5", "", ""},
                        {"2", "A2", "0", "DEV01262100000000002", "DENV",  "UNKNOWN", "FAM", "NFQ", "22.15", "22.15", "", "", "45200",  "", ""},
                        {"3", "A3", "0", "DEV01262100000000003", "ZIKV",  "UNKNOWN", "FAM", "NFQ", "30.67", "30.67", "", "", "890.2",  "", ""},
                        {"4", "A4", "0", "STD1",                  "CHIKV", "STANDARD", "FAM", "NFQ", "18.5",  "18.5",  "", "", "50000",  "", ""},
                        {"5", "A5", "0", "NTC",                   "CHIKV", "NTC",      "FAM", "NFQ", "",      "",      "", "", "0",      "", ""},
                };
                for (int r = 0; r < data.length; r++) {
                    Row row = results.createRow(headerRowOffset + 1 + r);
                    for (int c = 0; c < data[r].length; c++) {
                        Object v = data[r][c];
                        if (v instanceof String s && !s.isEmpty()) {
                            row.createCell(c).setCellValue(s);
                        }
                    }
                }

                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                wb.write(baos);
                wb.close();
                return new ByteArrayInputStream(baos.toByteArray());
            } catch (Exception e) {
                throw new RuntimeException("Failed to build QuantStudio-like synthetic fixture", e);
            }
        }

        @Test
        @DisplayName("Synthetic QS5-shape fixture (header row 20) → parser extracts clinical rows")
        void syntheticQS5Shape_headerAt20_parses() {
            InputStream xls = buildQuantStudioLikeWorkbook(20);

            List<ParsedResults> results = FileResultParser.parse(xls, QUANTSTUDIO_COLUMN_MAPPING);

            assertNotNull(results, "Parser returned null for synthetic QS5 fixture with header at row 20");
            assertFalse(results.isEmpty(),
                    "Parser extracted zero results from synthetic QS5 fixture with header at row 20. "
                            + "The findHeaderRow scan window may not cover row 20, or the Results sheet "
                            + "resolution is failing.");

            // Expect all 5 rows (3 UNKNOWN + 1 STANDARD + 1 NTC) until Phase A.3.2
            // adds Task filtering. Each accession maps to a single result, so
            // 5 input rows → 5 ParsedResults groups.
            int totalRows = results.stream().mapToInt(pr -> pr.results().size()).sum();
            assertTrue(totalRows >= 3,
                    "Expected at least 3 clinical results, got " + totalRows);

            Set<String> accessions = results.stream()
                    .map(ParsedResults::accessionNumber)
                    .collect(Collectors.toSet());
            assertTrue(accessions.contains("DEV01262100000000001"),
                    "Expected CHIKV sample accession not found");
            assertTrue(accessions.contains("DEV01262100000000002"),
                    "Expected DENV sample accession not found");
            assertTrue(accessions.contains("DEV01262100000000003"),
                    "Expected ZIKV sample accession not found");
        }

        @Test
        @DisplayName("Synthetic QS7-shape fixture (header row 50) → parser extracts clinical rows (covers Phase A.3.1 scan-window)")
        void syntheticQS7Shape_headerAt50_parses() {
            InputStream xls = buildQuantStudioLikeWorkbook(50);

            List<ParsedResults> results = FileResultParser.parse(xls, QUANTSTUDIO_COLUMN_MAPPING);

            // The current parser has a 60-row scan window in findHeaderRow.
            // Row 50 is within that window, so this SHOULD pass. Phase A.3.1
            // bumps the window to 200 for defensive margin. If this test
            // ever fails after a parser change, something tightened the
            // window.
            assertNotNull(results, "Parser returned null for QS7-shape fixture with header at row 50 — "
                    + "findHeaderRow scan window may have tightened below 60");
            assertFalse(results.isEmpty(),
                    "Parser extracted zero results from QS7-shape fixture with header at row 50");
        }

        @Test
        @DisplayName("Synthetic QS fixture with header at row 120 → parser finds header (within 200-row scan window)")
        void syntheticDeeperPreamble_headerAt120_parses() {
            // The parser's findHeaderRow scan window is 200 rows (see
            // FileResultParser.findHeaderRow, bumped from 60 in Phase A.3.1).
            // Row 120 is well within that window; the helper fills 20 metadata
            // rows then leaves a gap to row 120 and the parser must still find
            // the header.
            InputStream xls = buildQuantStudioLikeWorkbook(120);

            List<ParsedResults> results = FileResultParser.parse(xls, QUANTSTUDIO_COLUMN_MAPPING);

            assertNotNull(results,
                    "Parser returned null for QS-shape fixture with header at row 120 — "
                            + "the findHeaderRow scan window may have been tightened below 120.");
            assertFalse(results.isEmpty(),
                    "Parser extracted zero results from QS-shape fixture with header at row 120.");
        }
    }
}
