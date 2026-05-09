package org.itech.ahb.fhir;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.itech.ahb.fhir.HL7ResultParser.ParsedResults;
import org.itech.ahb.qc.ControlLotDto;
import org.itech.ahb.qc.QcRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests the FileResultParser lot-number enrichment path: when a sample is
 * classified as QC and the registered control-lot list contains a lot whose
 * lotNumber appears as a substring of the sampleId, the parser attaches the
 * matched lotNumber to the AnalyzerResult so OE's Tier 1 lot resolver fires.
 */
@DisplayName("FileResultParser — lotNumber substring scan")
class FileResultParserLotNumberTest {

    private static final Map<String, String> XLSX_MAPPINGS = Map.of(
            "Sample Name", "sampleId",
            "Target Name", "testCode",
            "Quantity Mean", "result");

    private static final Map<String, String> CSV_MAPPINGS = Map.of(
            "Sample Name", "sampleId",
            "Target Name", "testCode",
            "Quantity Mean", "result");

    private static final List<QcRule> LPC_HPC_RULES = List.of(
            new QcRule("SPECIMEN_ID_PREFIX", null, "LPC"),
            new QcRule("SPECIMEN_ID_PREFIX", null, "HPC"));

    private static final List<ControlLotDto> REGISTERED_LOTS = List.of(
            new ControlLotDto("LOT-LPC-26B", "LPC", 314),
            new ControlLotDto("LOT-HPC-26B", "HPC", 314));

    private InputStream buildXlsx(String sheetName, String[] headers, Object[][] rows) {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet(sheetName);
            Row hdr = sheet.createRow(0);
            for (int c = 0; c < headers.length; c++) {
                hdr.createCell(c).setCellValue(headers[c]);
            }
            for (int r = 0; r < rows.length; r++) {
                Row row = sheet.createRow(r + 1);
                for (int c = 0; c < rows[r].length; c++) {
                    Object v = rows[r][c];
                    if (v == null) {
                        row.createCell(c).setCellValue("");
                    } else if (v instanceof Number) {
                        row.createCell(c).setCellValue(((Number) v).doubleValue());
                    } else {
                        row.createCell(c).setCellValue(v.toString());
                    }
                }
            }
            workbook.write(out);
            return new ByteArrayInputStream(out.toByteArray());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Nested
    @DisplayName("Excel path (XLSX)")
    class Excel {

        @Test
        @DisplayName("LPC-prefixed sample with embedded lot → lotNumber + controlLevel attached")
        void lpcSampleEmbedsLot() {
            InputStream xlsx = buildXlsx("Results",
                    new String[]{"Well", "Sample Name", "Target Name", "Quantity Mean"},
                    new Object[][]{{"A1", "LPC-LOT-LPC-26B-001", "VIH-1", "32.5"}});

            List<ParsedResults> results = FileResultParser.parse(
                    xlsx, XLSX_MAPPINGS, null, LPC_HPC_RULES, REGISTERED_LOTS);

            assertNotNull(results);
            var ar = results.get(0).results().get(0);
            assertTrue(ar.isControl(), "row should be flagged as QC");
            assertEquals("LPC", ar.controlLevel(), "controlLevel from matched qcRule operand");
            assertEquals("LOT-LPC-26B", ar.lotNumber(),
                    "lotNumber from substring match against registered lots");
        }

        @Test
        @DisplayName("HPC-prefixed sample with embedded lot → HPC lot wins, not LPC")
        void hpcSampleResolvesHpcLot() {
            InputStream xlsx = buildXlsx("Results",
                    new String[]{"Well", "Sample Name", "Target Name", "Quantity Mean"},
                    new Object[][]{{"B1", "HPC-LOT-HPC-26B-001", "VIH-1", "25.5"}});

            List<ParsedResults> results = FileResultParser.parse(
                    xlsx, XLSX_MAPPINGS, null, LPC_HPC_RULES, REGISTERED_LOTS);

            assertNotNull(results);
            var ar = results.get(0).results().get(0);
            assertEquals("HPC", ar.controlLevel());
            assertEquals("LOT-HPC-26B", ar.lotNumber());
        }

        @Test
        @DisplayName("QC sample with no embedded lot → controlLevel set, lotNumber null")
        void qcWithoutEmbeddedLot() {
            InputStream xlsx = buildXlsx("Results",
                    new String[]{"Well", "Sample Name", "Target Name", "Quantity Mean"},
                    new Object[][]{{"A2", "LPC-NO-LOT-IN-NAME", "VIH-1", "32.5"}});

            List<ParsedResults> results = FileResultParser.parse(
                    xlsx, XLSX_MAPPINGS, null, LPC_HPC_RULES, REGISTERED_LOTS);

            assertNotNull(results);
            var ar = results.get(0).results().get(0);
            assertTrue(ar.isControl());
            assertEquals("LPC", ar.controlLevel());
            assertNull(ar.lotNumber(),
                    "no registered lot string appears in the sampleId — lotNumber stays null");
        }

        @Test
        @DisplayName("Empty controlLots list → controlLevel still set, lotNumber null")
        void emptyLotListSkipsLotEnrichment() {
            InputStream xlsx = buildXlsx("Results",
                    new String[]{"Well", "Sample Name", "Target Name", "Quantity Mean"},
                    new Object[][]{{"A3", "LPC-LOT-LPC-26B-001", "VIH-1", "32.5"}});

            List<ParsedResults> results = FileResultParser.parse(
                    xlsx, XLSX_MAPPINGS, null, LPC_HPC_RULES, java.util.Collections.emptyList());

            assertNotNull(results);
            var ar = results.get(0).results().get(0);
            assertTrue(ar.isControl());
            assertEquals("LPC", ar.controlLevel());
            assertNull(ar.lotNumber(),
                    "no lots registered → parser cannot enrich, lotNumber stays null");
        }

        @Test
        @DisplayName("Patient sample (no QC rule match) → no lotNumber attached even if id coincidentally embeds a lot string")
        void patientSampleSkipsLotScan() {
            InputStream xlsx = buildXlsx("Results",
                    new String[]{"Well", "Sample Name", "Target Name", "Quantity Mean"},
                    new Object[][]{{"C1", "PATIENT-LOT-LPC-26B-001", "VIH-1", "1500.0"}});

            // No QC rules → patient sample (rule-list empty path)
            List<ParsedResults> results = FileResultParser.parse(
                    xlsx, XLSX_MAPPINGS, null, java.util.Collections.emptyList(), REGISTERED_LOTS);

            assertNotNull(results);
            var ar = results.get(0).results().get(0);
            assertFalse(ar.isControl(), "no rule, no Task hint → patient sample");
            assertNull(ar.lotNumber(),
                    "lotNumber is only set on samples already classified as QC");
        }
    }

    @Nested
    @DisplayName("CSV path (parseCsv)")
    class Csv {

        @Test
        @DisplayName("LPC-prefixed sample with embedded lot → lotNumber + controlLevel attached")
        void lpcSampleEmbedsLotCsv() {
            String csv = "Sample Name,Target Name,Quantity Mean\n"
                    + "LPC-LOT-LPC-26B-001,VIH-1,32.5\n";

            List<ParsedResults> results = FileResultParser.parseCsv(
                    csv.getBytes(), CSV_MAPPINGS, ",", 0, null, LPC_HPC_RULES, REGISTERED_LOTS);

            assertNotNull(results);
            var ar = results.get(0).results().get(0);
            assertTrue(ar.isControl());
            assertEquals("LPC", ar.controlLevel());
            assertEquals("LOT-LPC-26B", ar.lotNumber());
        }

        @Test
        @DisplayName("Empty controlLots → controlLevel set, lotNumber null")
        void emptyLotListCsv() {
            String csv = "Sample Name,Target Name,Quantity Mean\n"
                    + "LPC-LOT-LPC-26B-001,VIH-1,32.5\n";

            List<ParsedResults> results = FileResultParser.parseCsv(
                    csv.getBytes(), CSV_MAPPINGS, ",", 0, null, LPC_HPC_RULES,
                    java.util.Collections.emptyList());

            assertNotNull(results);
            var ar = results.get(0).results().get(0);
            assertEquals("LPC", ar.controlLevel());
            assertNull(ar.lotNumber());
        }
    }
}
