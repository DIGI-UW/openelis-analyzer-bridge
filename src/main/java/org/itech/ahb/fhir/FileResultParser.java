package org.itech.ahb.fhir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import org.apache.commons.io.input.BOMInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.itech.ahb.fhir.FhirBundleBuilder.AnalyzerResult;

/**
 * Extracts lab results from Excel (xlsx/xls) and CSV files.
 *
 * <p>Ported from OE's {@code ExcelAnalyzerReader.readStream()} — same POI
 * parsing, header detection, and column mapping logic. The bridge owns this
 * parsing so OE receives a standard FHIR Bundle instead of raw binary.
 *
 * <p>Column mapping config comes from the analyzer profile's
 * {@code file_config.column_mapping} section, synced to the bridge at
 * registration time.
 */
@Slf4j
public class FileResultParser {

    private static final List<String> CONTROL_PREFIXES = Arrays.asList(
            // Molecular (QuantStudio, FluoroCycler)
            "CNEG", "CPOS", "NTC", "PTC",
            // ELISA plate readers (Tecan, Multiskan)
            "NEG", "POS", "NC", "PC", "BLANC", "BLANK");

    /**
     * Parse an Excel file and extract results using column mappings.
     *
     * @param inputStream the xlsx/xls file content
     * @param columnMappings map of spreadsheet column name → semantic field
     *        (e.g., {"Sample Name": "sampleId", "Target": "testCode", "CT": "result"})
     * @return list of parsed results grouped by accession, or null on failure
     */
    public static List<HL7ResultParser.ParsedResults> parse(
            InputStream inputStream, Map<String, String> columnMappings) {

        if (inputStream == null || columnMappings == null || columnMappings.isEmpty()) {
            log.warn("FileResultParser: null input or empty column mappings");
            return null;
        }

        try (Workbook workbook = WorkbookFactory.create(inputStream)) {
            Sheet sheet = resolveSheet(workbook);
            if (sheet == null) {
                log.warn("FileResultParser: empty workbook or missing sheet");
                return null;
            }

            DataFormatter formatter = new DataFormatter();
            int headerRowIndex = findHeaderRow(sheet, formatter);
            if (headerRowIndex < 0) {
                log.warn("FileResultParser: header row not found");
                return null;
            }

            Row headerRow = sheet.getRow(headerRowIndex);
            Map<String, Integer> headerIndex = buildHeaderIndex(headerRow, formatter);

            // Reverse mapping: semantic field → column index
            Map<String, Integer> fieldIndex = new HashMap<>();
            for (Map.Entry<String, String> mapping : columnMappings.entrySet()) {
                Integer colIdx = headerIndex.get(mapping.getKey());
                if (colIdx != null) {
                    fieldIndex.put(mapping.getValue(), colIdx);
                }
            }

            // Group results by accession number
            Map<String, List<AnalyzerResult>> resultsByAccession = new HashMap<>();

            for (int rowIdx = headerRowIndex + 1; rowIdx <= sheet.getLastRowNum(); rowIdx++) {
                Row row = sheet.getRow(rowIdx);
                if (row == null) continue;

                String sampleId = getCellValue(row, fieldIndex.get("sampleId"), formatter);
                String testCode = getCellValue(row, fieldIndex.get("testCode"), formatter);
                String result = getCellValue(row, fieldIndex.get("result"), formatter);
                String units = getCellValue(row, fieldIndex.get("units"), formatter);
                String interpretation = getCellValue(row, fieldIndex.get("interpretation"), formatter);
                String qcTask = getCellValue(row, fieldIndex.get("qcTask"), formatter);
                String testDate = getCellValue(row, fieldIndex.get("testDate"), formatter);

                if (sampleId == null || sampleId.isBlank()) continue;
                if (testCode == null || testCode.isBlank()) continue;

                // Use result or interpretation as the value
                String value = (result != null && !result.isBlank()) ? result : interpretation;
                if (value == null || value.isBlank()) continue;

                boolean isNumeric = isNumericValue(value);
                AnalyzerResult ar = isNumeric
                        ? AnalyzerResult.numeric(testCode, testCode, value, units)
                        : AnalyzerResult.text(testCode, testCode, value);
                ar = ar.withControl(isControlRow(sampleId, qcTask));
                if (testDate != null && !testDate.isBlank()) {
                    ar = ar.withTimestamp(testDate);
                }

                resultsByAccession.computeIfAbsent(sampleId, k -> new ArrayList<>()).add(ar);
            }

            if (resultsByAccession.isEmpty()) {
                log.warn("FileResultParser: no results extracted from file");
                return null;
            }

            List<HL7ResultParser.ParsedResults> allResults = new ArrayList<>();
            for (Map.Entry<String, List<AnalyzerResult>> entry : resultsByAccession.entrySet()) {
                allResults.add(new HL7ResultParser.ParsedResults(entry.getKey(), entry.getValue()));
            }
            return allResults;

        } catch (IOException e) {
            log.error("FileResultParser: failed to read Excel file: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * Parse a CSV/TSV text file and extract results using column mappings.
     *
     * @param content       raw file bytes (UTF-8 expected, BOM stripped automatically)
     * @param columnMappings map of CSV header name → semantic field
     * @param delimiter     CSV delimiter character (e.g., "," or ";")
     * @param skipRows      number of metadata rows to skip before header
     * @return list of parsed results grouped by accession, or null on failure
     */
    public static List<HL7ResultParser.ParsedResults> parseCsv(
            byte[] content, Map<String, String> columnMappings,
            String delimiter, int skipRows) {

        if (content == null || content.length == 0 || columnMappings == null || columnMappings.isEmpty()) {
            log.warn("FileResultParser.parseCsv: null/empty input or column mappings");
            return null;
        }

        // Strip BOM (UTF-8, UTF-16LE/BE, UTF-32) using Apache Commons IO
        String text;
        try (BOMInputStream bomIn = BOMInputStream.builder()
                .setInputStream(new ByteArrayInputStream(content))
                .setInclude(false)
                .get()) {
            text = new String(bomIn.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("FileResultParser.parseCsv: BOM stripping failed, falling back to raw", e);
            text = new String(content, StandardCharsets.UTF_8);
        }

        // Split into lines and skip metadata rows
        String[] allLines = text.split("\\r?\\n");
        if (allLines.length <= skipRows) {
            log.warn("FileResultParser.parseCsv: file has {} lines but skipRows={}", allLines.length, skipRows);
            return null;
        }

        StringBuilder csvContent = new StringBuilder();
        for (int i = skipRows; i < allLines.length; i++) {
            csvContent.append(allLines[i]).append("\n");
        }

        char delimChar = (delimiter != null && !delimiter.isEmpty()) ? delimiter.charAt(0) : ',';

        try {
            CSVFormat format = CSVFormat.DEFAULT.builder()
                    .setDelimiter(delimChar)
                    .setHeader()
                    .setSkipHeaderRecord(true)
                    .setIgnoreEmptyLines(true)
                    .setTrim(true)
                    .build();

            Map<String, List<AnalyzerResult>> resultsByAccession = new HashMap<>();

            try (StringReader reader = new StringReader(csvContent.toString())) {
                for (CSVRecord record : format.parse(reader)) {
                    String sampleId = getMappedValue(record, columnMappings, "sampleId");
                    if (sampleId == null || sampleId.isBlank()) {
                        // Some FILE profiles omit explicit sample mapping aliases.
                        sampleId = getFirstPresentValue(record,
                                "SampleID", "Sample ID", "SampleNumber", "Sample Number", "Sample Name");
                    }
                    String testCode = getMappedValue(record, columnMappings, "testCode");
                    if (testCode == null || testCode.isBlank()) {
                        // Some FILE profiles omit explicit testCode mapping for single-test analyzers.
                        testCode = getFirstPresentValue(record, "TestCode", "Test Code", "Target", "Test Name");
                    }
                    String result = getMappedValue(record, columnMappings, "result");
                    String units = getMappedValue(record, columnMappings, "units");
                    String interpretation = getMappedValue(record, columnMappings, "interpretation");
                    String qcTask = getMappedValue(record, columnMappings, "qcTask");
                    String testDate = getMappedValue(record, columnMappings, "testDate");
                    String dateTime = getMappedValue(record, columnMappings, "dateTime");

                    if (sampleId == null || sampleId.isBlank()) continue;
                    if (testCode == null || testCode.isBlank()) continue;

                    String value = (result != null && !result.isBlank()) ? result : interpretation;
                    if (value == null || value.isBlank()) continue;

                    boolean isNumeric = isNumericValue(value);
                    AnalyzerResult ar = isNumeric
                            ? AnalyzerResult.numeric(testCode, testCode, value, units)
                            : AnalyzerResult.text(testCode, testCode, value);
                    ar = ar.withControl(isControlRow(sampleId, qcTask));

                    // Use testDate or dateTime — fall back to dateTime if testDate is null or blank
                    String ts = (testDate != null && !testDate.isBlank()) ? testDate : dateTime;
                    if (ts != null && !ts.isBlank()) {
                        ar = ar.withTimestamp(ts);
                    }

                    resultsByAccession.computeIfAbsent(sampleId, k -> new ArrayList<>()).add(ar);
                }
            }

            if (resultsByAccession.isEmpty()) {
                log.warn("FileResultParser.parseCsv: no results extracted from CSV");
                return null;
            }

            List<HL7ResultParser.ParsedResults> allResults = new ArrayList<>();
            for (Map.Entry<String, List<AnalyzerResult>> entry : resultsByAccession.entrySet()) {
                allResults.add(new HL7ResultParser.ParsedResults(entry.getKey(), entry.getValue()));
            }

            log.info("FileResultParser.parseCsv: extracted {} accessions from CSV", allResults.size());
            return allResults;

        } catch (IOException e) {
            log.error("FileResultParser.parseCsv: failed to parse CSV: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * Get a mapped value from a CSV record. Looks up which CSV column maps to the
     * given semantic field, then reads that column from the record.
     */
    private static String getMappedValue(CSVRecord record, Map<String, String> columnMappings, String fieldName) {
        for (Map.Entry<String, String> mapping : columnMappings.entrySet()) {
            if (fieldName.equals(mapping.getValue())) {
                try {
                    String value = record.get(mapping.getKey());
                    return (value != null && !value.isBlank()) ? value.trim() : null;
                } catch (IllegalArgumentException e) {
                    // Column not found for this alias; try the next alias for the same semantic field.
                }
            }
        }
        return null;
    }

    private static String getFirstPresentValue(CSVRecord record, String... headers) {
        for (String header : headers) {
            try {
                String value = record.get(header);
                if (value != null && !value.isBlank()) {
                    return value.trim();
                }
            } catch (IllegalArgumentException ignored) {
                // Header alias not present in this record.
            }
        }
        return null;
    }

    /**
     * Resolve sheet — prefer "Results" sheet, fall back to first sheet.
     * Ported from ExcelAnalyzerReader.resolveSheet().
     */
    private static Sheet resolveSheet(Workbook workbook) {
        if (workbook.getNumberOfSheets() == 0) return null;
        Sheet byName = workbook.getSheet("Results");
        if (byName != null) return byName;
        return workbook.getSheetAt(0);
    }

    /**
     * Find header row. For QuantStudio-style files with metadata block, scans
     * for row where first cell equals "Well".
     * Ported from ExcelAnalyzerReader.findHeaderRow().
     */
    private static int findHeaderRow(Sheet sheet, DataFormatter formatter) {
        int firstRow = sheet.getFirstRowNum();
        Row first = sheet.getRow(firstRow);
        if (first == null) return -1;

        String firstCell = formatter.formatCellValue(first.getCell(0));
        if (firstCell != null && firstCell.trim().equals("Well")) {
            return firstRow;
        }
        // QuantStudio metadata block detection
        if (firstCell != null && (firstCell.contains("Block Type") || firstCell.contains("Experiment Name"))) {
            for (int r = firstRow + 1; r <= Math.min(firstRow + 60, sheet.getLastRowNum()); r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;
                String cell0 = formatter.formatCellValue(row.getCell(0));
                if (cell0 != null && cell0.trim().equals("Well")) {
                    return r;
                }
            }
        }
        return firstRow;
    }

    /**
     * Build header name → column index map.
     * Ported from ExcelAnalyzerReader.buildHeaderIndex().
     */
    private static Map<String, Integer> buildHeaderIndex(Row headerRow, DataFormatter formatter) {
        Map<String, Integer> index = new HashMap<>();
        if (headerRow == null) return index;
        for (int i = 0; i < headerRow.getLastCellNum(); i++) {
            Cell cell = headerRow.getCell(i);
            String name = formatter.formatCellValue(cell);
            if (name != null && !name.isBlank()) {
                index.put(name.trim(), i);
            }
        }
        return index;
    }

    private static String getCellValue(Row row, Integer colIndex, DataFormatter formatter) {
        if (row == null || colIndex == null) return null;
        Cell cell = row.getCell(colIndex);
        if (cell == null) return null;
        String value = formatter.formatCellValue(cell);
        return (value != null && !value.isBlank()) ? value.trim() : null;
    }

    private static boolean isNumericValue(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        // Values with comparison operators (<2, >100, <=5) are stored as text —
        // they represent qualitative assertions, not pure numbers, and cannot be
        // parsed as BigDecimal by FhirBundleBuilder.
        if (value.startsWith("<") || value.startsWith(">") || value.startsWith("≤") || value.startsWith("≥")) {
            return false;
        }
        try {
            Double.parseDouble(value);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static boolean isControlRow(String sampleId, String qcTask) {
        if (qcTask != null && "CONTROL".equalsIgnoreCase(qcTask.trim())) {
            return true;
        }
        if (sampleId == null) {
            return false;
        }
        String normalizedSampleId = sampleId.trim().toUpperCase();
        return CONTROL_PREFIXES.stream()
                .anyMatch(normalizedSampleId::startsWith);
    }
}
