package org.itech.ahb.fhir;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    }
}
