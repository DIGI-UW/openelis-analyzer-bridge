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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
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
import org.itech.ahb.qc.QcRule;
import org.itech.ahb.qc.QcRuleEvaluator;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

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

    /** OpenDocument XML namespaces used to navigate ODS content.xml. */
    private static final String ODS_TABLE_NS = "urn:oasis:names:tc:opendocument:xmlns:table:1.0";
    private static final String ODS_TEXT_NS = "urn:oasis:names:tc:opendocument:xmlns:text:1.0";

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
        return parse(inputStream, columnMappings, null, java.util.Collections.emptyList());
    }

    /**
     * 3-arg overload preserved for callers that only need the file-wide
     * test-code fallback (e.g. {@code FileMessageHandler}). Forwards to the
     * 4-arg canonical with an empty QC-rule list — the bridge's legacy
     * hardcoded QC detection still applies inside {@code isControlRow}
     * when no rules are configured.
     */
    public static List<HL7ResultParser.ParsedResults> parse(
            InputStream inputStream, Map<String, String> columnMappings, String perFileTestCode) {
        return parse(inputStream, columnMappings, perFileTestCode, java.util.Collections.emptyList());
    }

    /**
     * Parse an Excel file with an optional file-wide test code and
     * configurable QC identification rules pulled from OE.
     *
     * @param perFileTestCode event-scoped fallback test code; null or
     *        blank drops rows with no per-row identity
     * @param qcRules         QC identification rules (FR-15); empty list
     *        defers to legacy hardcoded QC detection in {@code isControlRow}
     */
    public static List<HL7ResultParser.ParsedResults> parse(
            InputStream inputStream, Map<String, String> columnMappings,
            String perFileTestCode, List<QcRule> qcRules) {

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
                String ctValue = getCellValue(row, fieldIndex.get("ctValue"), formatter);
                String units = getCellValue(row, fieldIndex.get("units"), formatter);
                String interpretation = getCellValue(row, fieldIndex.get("interpretation"), formatter);
                String qcTask = getCellValue(row, fieldIndex.get("qcTask"), formatter);
                String testDate = getCellValue(row, fieldIndex.get("testDate"), formatter);

                if (sampleId == null || sampleId.isBlank()) continue;
                if (testCode == null || testCode.isBlank()) {
                    if (perFileTestCode != null && !perFileTestCode.isBlank()) {
                        testCode = perFileTestCode.trim();
                    } else {
                        continue;
                    }
                }

                // Use result → ctValue → interpretation as the value.
                // QuantStudio HIV Viral Load fills `Quantity Mean` (mapped to
                // `result`) with copies/mL. QuantStudio Arbovirus PCR leaves
                // `Quantity Mean` empty because arbovirus targets are
                // qualitative — only the `CT` column (mapped to `ctValue`)
                // carries the cycle threshold. Falling back from result →
                // ctValue lets a single profile serve both workflows without
                // a rewrite. Interpretation is a last-resort text slot.
                String value = (result != null && !result.isBlank()) ? result
                        : (ctValue != null && !ctValue.isBlank()) ? ctValue
                        : interpretation;
                if (value == null || value.isBlank()) continue;

                boolean isNumeric = isNumericValue(value);
                AnalyzerResult ar = isNumeric
                        ? AnalyzerResult.numeric(testCode, testCode, value, units)
                        : AnalyzerResult.text(testCode, testCode, value);
                String matchedLevel = matchControlRule(sampleId, qcTask, qcRules);
                boolean isCtrl = matchedLevel != null || isControlRow(sampleId, qcTask);
                ar = ar.withControl(isCtrl);
                if (matchedLevel != null) {
                    ar = ar.withControlLevel(matchedLevel);
                }
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
     * Parse an OpenDocument Spreadsheet (.ods) file and extract results.
     *
     * <p>Header row is located by scanning for a cell whose text equals
     * {@code "Sample ID"}. {@code Row} + {@code Col} columns, when both
     * present, are composed into the {@code position} semantic field
     * inline (not declared in {@code column_mapping}). If {@code Calc. Conc.}
     * is absent or all blank, {@code Result} is promoted to the
     * {@code result} semantic field so qualitative rows still produce a
     * populated value.
     */
    public static List<HL7ResultParser.ParsedResults> parseOds(
            InputStream inputStream, Map<String, String> columnMappings, String perFileTestCode) {

        if (inputStream == null || columnMappings == null || columnMappings.isEmpty()) {
            log.warn("FileResultParser.parseOds: null input or empty column mappings");
            return null;
        }

        List<List<String>> table;
        try {
            table = readOdsContentXml(inputStream);
        } catch (IOException | SAXException | ParserConfigurationException e) {
            log.error("FileResultParser.parseOds: failed to read ODS file: {}", e.getMessage(), e);
            return null;
        }

        if (table == null || table.isEmpty()) {
            log.warn("FileResultParser.parseOds: no usable table found in content.xml");
            return null;
        }

        int headerRowIdx = findOdsHeaderRow(table);
        if (headerRowIdx < 0) {
            log.warn("FileResultParser.parseOds: header row not found (no row contains 'Sample ID')");
            return null;
        }

        List<String> headers = table.get(headerRowIdx);
        Map<String, Integer> headerIndex = new LinkedHashMap<>();
        for (int i = 0; i < headers.size(); i++) {
            String h = headers.get(i);
            if (h != null && !h.isBlank()) {
                headerIndex.put(h.trim(), i);
            }
        }

        Map<String, Integer> fieldIndex = new HashMap<>();
        for (Map.Entry<String, String> mapping : columnMappings.entrySet()) {
            Integer colIdx = headerIndex.get(mapping.getKey());
            if (colIdx != null) {
                fieldIndex.put(mapping.getValue(), colIdx);
            }
        }

        // If Calc. Conc. is missing or all-blank, promote Result to the
        // `result` semantic field so qualitative rows still produce a value.
        Integer calcConcIdx = headerIndex.get("Calc. Conc.");
        boolean calcConcPopulated = false;
        if (calcConcIdx != null) {
            for (int r = headerRowIdx + 1; r < table.size(); r++) {
                String v = getRowValue(table.get(r), calcConcIdx);
                if (v != null && !v.isBlank()) { calcConcPopulated = true; break; }
            }
        }
        if (!calcConcPopulated) {
            Integer resultColIdx = headerIndex.get("Result");
            if (resultColIdx != null) {
                fieldIndex.put("result", resultColIdx);
            }
        }

        Map<String, List<AnalyzerResult>> resultsByAccession = new HashMap<>();

        for (int rIdx = headerRowIdx + 1; rIdx < table.size(); rIdx++) {
            List<String> row = table.get(rIdx);
            if (isRowAllEmpty(row)) continue;

            String sampleId = getRowValue(row, fieldIndex.get("sampleId"));
            String testCode = getRowValue(row, fieldIndex.get("testCode"));
            String result = getRowValue(row, fieldIndex.get("result"));
            String interpretation = getRowValue(row, fieldIndex.get("interpretation"));
            String qcTask = getRowValue(row, fieldIndex.get("qcTask"));
            String units = getRowValue(row, fieldIndex.get("units"));
            String ctValue = getRowValue(row, fieldIndex.get("ctValue"));

            if (sampleId == null || sampleId.isBlank()) continue;
            if (testCode == null || testCode.isBlank()) {
                if (perFileTestCode != null && !perFileTestCode.isBlank()) {
                    testCode = perFileTestCode.trim();
                } else {
                    continue;
                }
            }

            String value = (result != null && !result.isBlank()) ? result
                    : (ctValue != null && !ctValue.isBlank()) ? ctValue
                    : interpretation;
            if (value == null || value.isBlank()) continue;

            boolean isNumeric = isNumericValue(value);
            AnalyzerResult ar = isNumeric
                    ? AnalyzerResult.numeric(testCode, testCode, value, units)
                    : AnalyzerResult.text(testCode, testCode, value);
            ar = ar.withControl(isControlRow(sampleId, qcTask));

            resultsByAccession.computeIfAbsent(sampleId, k -> new ArrayList<>()).add(ar);
        }

        if (resultsByAccession.isEmpty()) {
            log.warn("FileResultParser.parseOds: no results extracted from ODS");
            return null;
        }

        List<HL7ResultParser.ParsedResults> allResults = new ArrayList<>();
        for (Map.Entry<String, List<AnalyzerResult>> entry : resultsByAccession.entrySet()) {
            allResults.add(new HL7ResultParser.ParsedResults(entry.getKey(), entry.getValue()));
        }
        log.info("FileResultParser.parseOds: extracted {} accessions from ODS", allResults.size());
        return allResults;
    }

    /**
     * Read an ODS file's {@code content.xml} and return its first non-empty
     * table as a row-major list of cell text values. Public for reuse by
     * the file-identity scanner.
     */
    public static List<List<String>> readOdsContentXml(InputStream inputStream)
            throws IOException, SAXException, ParserConfigurationException {
        try (ZipInputStream zis = new ZipInputStream(inputStream)) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if ("content.xml".equals(e.getName())) {
                    DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
                    dbf.setNamespaceAware(true);
                    // Defensive: disable DTD + external entity processing
                    dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                    dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
                    dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
                    DocumentBuilder db = dbf.newDocumentBuilder();
                    Document doc = db.parse(zis);
                    NodeList tables = doc.getElementsByTagNameNS(ODS_TABLE_NS, "table");
                    for (int t = 0; t < tables.getLength(); t++) {
                        Element tbl = (Element) tables.item(t);
                        List<List<String>> rows = extractOdsTableRows(tbl);
                        boolean anyNonEmpty = false;
                        for (List<String> r : rows) {
                            if (!isRowAllEmpty(r)) { anyNonEmpty = true; break; }
                        }
                        if (anyNonEmpty) return rows;
                    }
                    return null;
                }
            }
        }
        return null;
    }

    private static List<List<String>> extractOdsTableRows(Element table) {
        List<List<String>> rows = new ArrayList<>();
        NodeList rowNodes = table.getElementsByTagNameNS(ODS_TABLE_NS, "table-row");
        for (int r = 0; r < rowNodes.getLength(); r++) {
            Element rowEl = (Element) rowNodes.item(r);
            List<String> cells = new ArrayList<>();
            NodeList cellNodes = rowEl.getElementsByTagNameNS(ODS_TABLE_NS, "table-cell");
            for (int c = 0; c < cellNodes.getLength(); c++) {
                Element cellEl = (Element) cellNodes.item(c);
                String text = extractOdsCellText(cellEl);
                int repeat = 1;
                String repeatAttr = cellEl.getAttributeNS(ODS_TABLE_NS, "number-columns-repeated");
                if (repeatAttr != null && !repeatAttr.isBlank()) {
                    try {
                        repeat = Integer.parseInt(repeatAttr);
                    } catch (NumberFormatException ignored) { }
                }
                // Empty trailing repeats blow up row length — collapse them
                // to a single blank cell to preserve column alignment for
                // data cells without inflating memory.
                if ((text == null || text.isBlank()) && repeat > 64) {
                    cells.add("");
                } else {
                    int capped = Math.min(repeat, 1024);
                    for (int i = 0; i < capped; i++) cells.add(text == null ? "" : text);
                }
            }
            rows.add(cells);
        }
        return rows;
    }

    private static String extractOdsCellText(Element cellEl) {
        NodeList ps = cellEl.getElementsByTagNameNS(ODS_TEXT_NS, "p");
        if (ps.getLength() == 0) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ps.getLength(); i++) {
            if (i > 0) sb.append('\n');
            sb.append(ps.item(i).getTextContent());
        }
        String s = sb.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private static int findOdsHeaderRow(List<List<String>> table) {
        for (int r = 0; r < table.size(); r++) {
            List<String> row = table.get(r);
            for (String cell : row) {
                if (cell != null && "Sample ID".equalsIgnoreCase(cell.trim())) {
                    return r;
                }
            }
        }
        return -1;
    }

    private static boolean isRowAllEmpty(List<String> row) {
        if (row == null || row.isEmpty()) return true;
        for (String cell : row) {
            if (cell != null && !cell.isBlank()) return false;
        }
        return true;
    }

    private static String getRowValue(List<String> row, Integer colIdx) {
        if (row == null || colIdx == null || colIdx < 0 || colIdx >= row.size()) return null;
        String v = row.get(colIdx);
        return (v != null && !v.isBlank()) ? v.trim() : null;
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
        return parseCsv(content, columnMappings, delimiter, skipRows, null, java.util.Collections.emptyList());
    }

    /** See {@link #parse(InputStream, Map, String, List)} for {@code perFileTestCode} semantics. */
    public static List<HL7ResultParser.ParsedResults> parseCsv(
            byte[] content, Map<String, String> columnMappings,
            String delimiter, int skipRows, String perFileTestCode) {
        return parseCsv(content, columnMappings, delimiter, skipRows, perFileTestCode, java.util.Collections.emptyList());
    }

    /**
     * Parse a CSV file with an optional file-wide test code and configurable
     * QC identification rules.
     */
    public static List<HL7ResultParser.ParsedResults> parseCsv(
            byte[] content, Map<String, String> columnMappings,
            String delimiter, int skipRows, String perFileTestCode, List<QcRule> qcRules) {

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

        char delimChar = (delimiter != null && !delimiter.isEmpty()) ? delimiter.charAt(0) : ',';

        // QuantStudio Design & Analysis CSV exports carry a `* Key = Value`
        // metadata preamble + multi-section layout (`[Sample Setup]` then
        // `[Results]`) before the actual tabular header. Mirrors the Excel
        // path's findHeaderRow logic so QuantStudio CSV exports parse the
        // same way the .xlsx exports already do.
        //
        // Strategy: start at the configured `skipRows` (preserves existing
        // behavior for non-QuantStudio CSVs). Scan up to 200 more lines for
        // a row whose first delimiter-separated field is exactly "Well" —
        // the QuantStudio header marker. Prefer the LAST such header before
        // EOF, since the Sample Setup section also starts with "Well" but
        // the Results section is what we want.
        int headerLineIndex = -1;
        int scanEnd = Math.min(allLines.length, skipRows + 200);
        for (int i = skipRows; i < scanEnd; i++) {
            String line = allLines[i];
            if (line == null || line.isBlank()) continue;
            int firstDelim = line.indexOf(delimChar);
            String firstField = (firstDelim >= 0) ? line.substring(0, firstDelim) : line;
            if ("Well".equals(firstField.trim())) {
                headerLineIndex = i;
                // Don't break — keep scanning for a later "Well" row.
            }
        }

        StringBuilder csvContent = new StringBuilder();
        int contentStart = (headerLineIndex >= 0) ? headerLineIndex : skipRows;
        for (int i = contentStart; i < allLines.length; i++) {
            csvContent.append(allLines[i]).append("\n");
        }

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
                    String ctValue = getMappedValue(record, columnMappings, "ctValue");
                    String units = getMappedValue(record, columnMappings, "units");
                    String interpretation = getMappedValue(record, columnMappings, "interpretation");
                    String qcTask = getMappedValue(record, columnMappings, "qcTask");
                    String testDate = getMappedValue(record, columnMappings, "testDate");
                    String dateTime = getMappedValue(record, columnMappings, "dateTime");

                    if (sampleId == null || sampleId.isBlank()) continue;
                    if (testCode == null || testCode.isBlank()) {
                        if (perFileTestCode != null && !perFileTestCode.isBlank()) {
                            testCode = perFileTestCode.trim();
                        } else {
                            continue;
                        }
                    }

                    // Mirror the Excel parse() fallback chain: result → ctValue
                    // → interpretation. QuantStudio Arbovirus PCR + QuantStudio
                    // QC samples leave `Quantity Mean` (mapped to `result`)
                    // empty since the meaningful number is in `CT` (mapped to
                    // `ctValue`). Without this fallback, every QuantStudio QC
                    // row gets dropped at the value check.
                    String value = (result != null && !result.isBlank()) ? result
                            : (ctValue != null && !ctValue.isBlank()) ? ctValue
                            : interpretation;
                    if (value == null || value.isBlank()) continue;

                    boolean isNumeric = isNumericValue(value);
                    AnalyzerResult ar = isNumeric
                            ? AnalyzerResult.numeric(testCode, testCode, value, units)
                            : AnalyzerResult.text(testCode, testCode, value);
                    String matchedLevel = matchControlRule(sampleId, qcTask, qcRules);
                    boolean isCtrl = matchedLevel != null || isControlRow(sampleId, qcTask);
                    ar = ar.withControl(isCtrl);
                    if (matchedLevel != null) {
                        ar = ar.withControlLevel(matchedLevel);
                    }

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
     * Find header row. For QuantStudio-style files with a metadata preamble
     * (row 0 contains {@code "Block Type"} or {@code "Experiment Name"}),
     * scan forward for a row whose first cell is {@code "Well"}.
     * <p>
     * Scan window: 200 rows. This is defensive margin — Madagascar's
     * CVVIH 24 07 2024 serie 02 QuantStudio 7 Flex export has the header
     * row at row 50, the Arbo-extraitQS5.xls at row 20, and QS SDS exports
     * in general put the header between row 15 and row 50 depending on
     * how many calibration / experiment metadata fields the tech filled in.
     * A 60-row window (previous value) covered both known real files but
     * gave only a 10-row margin on the CVVIH case; 200 covers any
     * reasonable preamble size.
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
            for (int r = firstRow + 1; r <= Math.min(firstRow + 200, sheet.getLastRowNum()); r++) {
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

    /**
     * FR-15: rule-based QC detection with fallback to hardcoded logic.
     */
    static boolean isControlRow(String sampleId, String qcTask, List<QcRule> qcRules) {
        return matchControlRule(sampleId, qcTask, qcRules) != null
                || isControlRow(sampleId, qcTask);
    }

    /**
     * Find the QC rule that classifies the sample as control, returning
     * its operand (the level identifier — "LPC", "CNEG", "STANDARD",
     * etc.) so the FHIR Observation can carry controlLevel metadata for
     * OE's QCResultProcessingService. Returns null when no rule matches
     * (caller should fall through to legacy detection).
     */
    static String matchControlRule(String sampleId, String qcTask, List<QcRule> qcRules) {
        if (qcRules == null || qcRules.isEmpty()) {
            return null;
        }
        Map<String, String> fieldValues = new HashMap<>();
        if (qcTask != null) {
            fieldValues.put("QC_TASK", qcTask);
        }
        return QcRuleEvaluator.findMatchingRule(qcRules, sampleId, fieldValues)
                .map(QcRule::operand)
                .orElse(null);
    }

    private static boolean isControlRow(String sampleId, String qcTask) {
        // Non-clinical task / type values are treated as controls so they're
        // preserved in the staging table but hidden from the clinical review
        // UI by default (OE staging filters by isControl). Covers:
        //   • "CONTROL" — generic control flag (pre-existing)
        //   • "STANDARD" / "STD" — QuantStudio calibration curve rows
        //   • "NTC" — No Template Control (QuantStudio)
        //   • "CALIBRATOR" — plate-reader calibration wells
        //   • "POSITIVE" / "NEGATIVE" — Bruker Fluorocycler XT control wells.
        //     The Fluorocycler "Type" column distinguishes clinical samples
        //     (Type=Unknown) from control wells (Type=Positive for positive
        //     control, Type=Negative for negative control). These are NOT
        //     result interpretations — they're well purposes, same semantic
        //     as QuantStudio's Task=STANDARD. In practice no analyzer we
        //     handle uses Type=Positive to mean "this clinical sample is
        //     positive"; that lives in the Result column instead.
        if (qcTask != null) {
            String t = qcTask.trim().toUpperCase();
            if (t.equals("CONTROL") || t.equals("STANDARD") || t.equals("STD")
                    || t.equals("NTC") || t.equals("CALIBRATOR")
                    || t.equals("POSITIVE") || t.equals("NEGATIVE")) {
                return true;
            }
        }
        if (sampleId == null) {
            return false;
        }
        String normalizedSampleId = sampleId.trim().toUpperCase();
        return CONTROL_PREFIXES.stream()
                .anyMatch(normalizedSampleId::startsWith);
    }
}
