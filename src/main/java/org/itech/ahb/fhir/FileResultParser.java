package org.itech.ahb.fhir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.io.input.BOMInputStream;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.itech.ahb.fhir.FhirBundleBuilder.AnalyzerResult;
import org.itech.ahb.profile.ControlRecognitionRule;
import org.itech.ahb.profile.ControlResultRecognition;
import org.itech.ahb.profile.ControlResultRecognitionEvaluator;
import org.itech.ahb.profile.TabularResultValueSelection;
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

    /** OpenDocument XML namespaces used to navigate ODS content.xml. */
    private static final String ODS_TABLE_NS = "urn:oasis:names:tc:opendocument:xmlns:table:1.0";
    private static final String ODS_TEXT_NS = "urn:oasis:names:tc:opendocument:xmlns:text:1.0";

    /**
     * Parse an Excel file using a file-wide test code and the pinned profile's
     * control-result recognition.
     *
     * @param perFileTestCode event-scoped fallback test code; null or
     *        blank drops rows with no per-row identity
     * @param recognition profile-owned control-result recognition
     */
    public static List<HL7ResultParser.ParsedResults> parse(
            InputStream inputStream, Map<String, String> columnMappings,
            String perFileTestCode, ControlResultRecognition recognition) {
        return parse(
                inputStream, columnMappings, perFileTestCode, recognition, null,
                TabularResultValueSelection.resultOnly());
    }

    public static List<HL7ResultParser.ParsedResults> parse(
            InputStream inputStream, Map<String, String> columnMappings,
            String perFileTestCode, ControlResultRecognition recognition,
            TabularFileLayout layout) {
        return parse(
                inputStream, columnMappings, perFileTestCode, recognition, layout,
                TabularResultValueSelection.resultOnly());
    }

    public static List<HL7ResultParser.ParsedResults> parse(
            InputStream inputStream, Map<String, String> columnMappings,
            String perFileTestCode, ControlResultRecognition recognition,
            TabularFileLayout layout, TabularResultValueSelection resultSelection) {

        if (inputStream == null || columnMappings == null || columnMappings.isEmpty()) {
            log.warn("FileResultParser: null input or empty column mappings");
            return null;
        }

        try (Workbook workbook = WorkbookFactory.create(inputStream)) {
            DataFormatter formatter = new DataFormatter();
            SheetHeader sheetHeader = locateSheetHeader(workbook, formatter, layout);
            if (sheetHeader == null) {
                log.warn("FileResultParser: profile-configured result header not found");
                return null;
            }

            Sheet sheet = sheetHeader.sheet();
            int headerRowIndex = sheetHeader.headerRowIndex();

            Row headerRow = sheet.getRow(headerRowIndex);
            Map<String, Integer> headerIndex = buildHeaderIndex(headerRow, formatter);

            Map<String, List<Integer>> fieldIndexes =
                    buildFieldIndexes(headerIndex, columnMappings);

            // Group results by accession number
            Map<String, List<AnalyzerResult>> resultsByAccession = new HashMap<>();

            for (int rowIdx = headerRowIndex + 1; rowIdx <= sheet.getLastRowNum(); rowIdx++) {
                Row row = sheet.getRow(rowIdx);
                if (row == null) continue;

                String sampleId = getCellValue(row, fieldIndexes.get("sampleId"), formatter);
                String testCode = getCellValue(row, fieldIndexes.get("testCode"), formatter);
                String units = getCellValue(row, fieldIndexes.get("units"), formatter);
                String qcTask = getCellValue(row, fieldIndexes.get("qcTask"), formatter);
                String testDate = getCellValue(row, fieldIndexes.get("testDate"), formatter);

                if (sampleId == null || sampleId.isBlank()) continue;
                if (testCode == null || testCode.isBlank()) {
                    if (perFileTestCode != null && !perFileTestCode.isBlank()) {
                        testCode = perFileTestCode.trim();
                    } else {
                        continue;
                    }
                }

                String value = resultSelection.select(
                        field -> getCellValue(row, fieldIndexes.get(field), formatter));
                if (value == null || value.isBlank()) continue;

                boolean isNumeric = isNumericValue(value);
                AnalyzerResult ar = isNumeric
                        ? AnalyzerResult.numeric(testCode, testCode, value, units)
                        : AnalyzerResult.text(testCode, testCode, value);
                ControlResultRecognitionEvaluator.Assessment assessment =
                        evaluateControl(sampleId, qcTask, recognition);
                ar = ar.withControlRecognition(assessment)
                        .withControl(assessment.matchedRule().isPresent());
                if (assessment.matchedRule().isPresent()) {
                    ControlRecognitionRule rule = assessment.matchedRule().orElseThrow();
                    ar = ar.withControlLevel(rule.controlLevel())
                            .withControlType(rule.controlType());
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
     * <p>Header and result-value selection come from the pinned profile.
     */
    public static List<HL7ResultParser.ParsedResults> parseOds(
            InputStream inputStream, Map<String, String> columnMappings,
            String perFileTestCode, ControlResultRecognition recognition) {
        return parseOds(
                inputStream, columnMappings, perFileTestCode, recognition, null,
                TabularResultValueSelection.resultOnly());
    }

    public static List<HL7ResultParser.ParsedResults> parseOds(
            InputStream inputStream, Map<String, String> columnMappings,
            String perFileTestCode, ControlResultRecognition recognition,
            TabularFileLayout layout, TabularResultValueSelection resultSelection) {

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

        int headerRowIdx = findOdsHeaderRow(table, columnMappings, layout, resultSelection);
        if (headerRowIdx < 0) {
            log.warn("FileResultParser.parseOds: profile-configured result header not found");
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

        Map<String, List<Integer>> fieldIndexes =
                buildFieldIndexes(headerIndex, columnMappings);

        Map<String, List<AnalyzerResult>> resultsByAccession = new HashMap<>();

        for (int rIdx = headerRowIdx + 1; rIdx < table.size(); rIdx++) {
            List<String> row = table.get(rIdx);
            if (isRowAllEmpty(row)) continue;

            String sampleId = getRowValue(row, fieldIndexes.get("sampleId"));
            String testCode = getRowValue(row, fieldIndexes.get("testCode"));
            String qcTask = getRowValue(row, fieldIndexes.get("qcTask"));
            String units = getRowValue(row, fieldIndexes.get("units"));

            if (sampleId == null || sampleId.isBlank()) continue;
            if (testCode == null || testCode.isBlank()) {
                if (perFileTestCode != null && !perFileTestCode.isBlank()) {
                    testCode = perFileTestCode.trim();
                } else {
                    continue;
                }
            }

            String value = resultSelection.select(
                    field -> getRowValue(row, fieldIndexes.get(field)));
            if (value == null || value.isBlank()) continue;

            boolean isNumeric = isNumericValue(value);
            AnalyzerResult ar = isNumeric
                    ? AnalyzerResult.numeric(testCode, testCode, value, units)
                    : AnalyzerResult.text(testCode, testCode, value);
            ControlResultRecognitionEvaluator.Assessment assessment =
                    evaluateControl(sampleId, qcTask, recognition);
            ar = ar.withControlRecognition(assessment)
                    .withControl(assessment.matchedRule().isPresent());
            if (assessment.matchedRule().isPresent()) {
                ControlRecognitionRule rule = assessment.matchedRule().orElseThrow();
                ar = ar.withControlLevel(rule.controlLevel())
                        .withControlType(rule.controlType());
            }

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

    private static int findOdsHeaderRow(
            List<List<String>> table, Map<String, String> columnMappings,
            TabularFileLayout layout, TabularResultValueSelection resultSelection) {
        Set<String> sampleHeaders = mappedHeaders(columnMappings, Set.of("sampleId"));
        Set<String> resultHeaders = mappedHeaders(
                columnMappings, new LinkedHashSet<>(resultSelection.semanticFields()));
        for (int r = 0; r < table.size(); r++) {
            List<String> row = table.get(r);
            if (layout != null) {
                String firstCell = getRowValue(row, 0);
                if (layout.headerMarker().equals(firstCell)) return r;
                continue;
            }
            Set<String> cells = new LinkedHashSet<>();
            row.stream()
                    .filter(cell -> cell != null && !cell.isBlank())
                    .map(String::trim)
                    .forEach(cells::add);
            if (cells.stream().anyMatch(sampleHeaders::contains)
                    && cells.stream().anyMatch(resultHeaders::contains)) return r;
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

    private static String getRowValue(List<String> row, List<Integer> columnIndexes) {
        if (columnIndexes == null) return null;
        for (Integer columnIndex : columnIndexes) {
            String value = getRowValue(row, columnIndex);
            if (value != null) return value;
        }
        return null;
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
    /**
     * Parse CSV using the pinned profile's control-result recognition.
     */
    public static List<HL7ResultParser.ParsedResults> parseCsv(
            byte[] content, Map<String, String> columnMappings,
            String delimiter, int skipRows, String perFileTestCode,
            ControlResultRecognition recognition) {
        return parseCsv(
                content, columnMappings, delimiter, skipRows, perFileTestCode,
                recognition, null);
    }

    public static List<HL7ResultParser.ParsedResults> parseCsv(
            byte[] content, Map<String, String> columnMappings,
            String delimiter, int skipRows, String perFileTestCode,
            ControlResultRecognition recognition,
            TabularFileLayout layout) {
        return parseCsv(
                content, columnMappings, delimiter, skipRows, perFileTestCode,
                recognition, layout, TabularResultValueSelection.resultOnly());
    }

    public static List<HL7ResultParser.ParsedResults> parseCsv(
            byte[] content, Map<String, String> columnMappings,
            String delimiter, int skipRows, String perFileTestCode,
            ControlResultRecognition recognition, TabularFileLayout layout,
            TabularResultValueSelection resultSelection) {

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

        int headerLineIndex = skipRows;
        if (layout != null) {
            headerLineIndex = -1;
            int scanEnd = Math.min(allLines.length, skipRows + layout.maxRowsToScan());
            for (int i = skipRows; i < scanEnd; i++) {
                String line = allLines[i];
                if (line == null || line.isBlank()) continue;
                int firstDelim = line.indexOf(delimChar);
                String firstField = (firstDelim >= 0) ? line.substring(0, firstDelim) : line;
                if (layout.headerMarker().equals(firstField.trim())) {
                    headerLineIndex = i;
                }
            }
            if (headerLineIndex < 0) {
                log.warn("FileResultParser.parseCsv: profile-configured header marker not found");
                return null;
            }
        }

        StringBuilder csvContent = new StringBuilder();
        for (int i = headerLineIndex; i < allLines.length; i++) {
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
                    String testCode = getMappedValue(record, columnMappings, "testCode");
                    String units = getMappedValue(record, columnMappings, "units");
                    String qcTask = getMappedValue(record, columnMappings, "qcTask");
                    String testDate = getMappedValue(record, columnMappings, "testDate");

                    if (sampleId == null || sampleId.isBlank()) continue;
                    if (testCode == null || testCode.isBlank()) {
                        if (perFileTestCode != null && !perFileTestCode.isBlank()) {
                            testCode = perFileTestCode.trim();
                        } else {
                            continue;
                        }
                    }

                    String value = resultSelection.select(
                            field -> getMappedValue(record, columnMappings, field));
                    if (value == null || value.isBlank()) continue;

                    boolean isNumeric = isNumericValue(value);
                    AnalyzerResult ar = isNumeric
                            ? AnalyzerResult.numeric(testCode, testCode, value, units)
                            : AnalyzerResult.text(testCode, testCode, value);
                    ControlResultRecognitionEvaluator.Assessment assessment =
                            evaluateControl(sampleId, qcTask, recognition);
                    ar = ar.withControlRecognition(assessment)
                            .withControl(assessment.matchedRule().isPresent());
                    if (assessment.matchedRule().isPresent()) {
                        ControlRecognitionRule rule = assessment.matchedRule().orElseThrow();
                        ar = ar.withControlLevel(rule.controlLevel())
                                .withControlType(rule.controlType());
                    }

                    if (testDate != null && !testDate.isBlank()) {
                        ar = ar.withTimestamp(testDate);
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
                    if (value != null && !value.isBlank()) {
                        return value.trim();
                    }
                } catch (IllegalArgumentException e) {
                    // Column not found for this alias; try the next alias for the same semantic field.
                }
            }
        }
        return null;
    }

    private static SheetHeader locateSheetHeader(
            Workbook workbook, DataFormatter formatter, TabularFileLayout layout) {
        if (workbook.getNumberOfSheets() == 0) return null;
        if (layout == null) {
            Sheet first = workbook.getSheetAt(0);
            return new SheetHeader(first, first.getFirstRowNum());
        }

        Set<String> candidateNames = new LinkedHashSet<>();
        for (String preferredName : layout.preferredSheetNames()) {
            if (workbook.getSheet(preferredName) != null) {
                candidateNames.add(preferredName);
            }
            if (candidateNames.size() == layout.maxSheetsToScan()) break;
        }
        for (int index = 0;
                index < workbook.getNumberOfSheets() && candidateNames.size() < layout.maxSheetsToScan();
                index++) {
            candidateNames.add(workbook.getSheetName(index));
        }

        for (String candidateName : candidateNames) {
            Sheet sheet = workbook.getSheet(candidateName);
            int headerRowIndex = findHeaderRow(sheet, formatter, layout);
            if (headerRowIndex >= 0) {
                return new SheetHeader(sheet, headerRowIndex);
            }
        }
        return null;
    }

    private static int findHeaderRow(
            Sheet sheet, DataFormatter formatter, TabularFileLayout layout) {
        int firstRow = sheet.getFirstRowNum();
        int lastRow = Math.min(sheet.getLastRowNum(), firstRow + layout.maxRowsToScan() - 1);
        for (int rowIndex = firstRow; rowIndex <= lastRow; rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) continue;
            String firstCell = formatter.formatCellValue(row.getCell(0));
            if (layout.headerMarker().equals(firstCell.trim())) {
                return rowIndex;
            }
        }
        return -1;
    }

    private record SheetHeader(Sheet sheet, int headerRowIndex) {}

    /**
     * Build header name → column index map.
     * Ported from ExcelAnalyzerReader.buildHeaderIndex().
     */
    private static Map<String, Integer> buildHeaderIndex(Row headerRow, DataFormatter formatter) {
        Map<String, Integer> index = new LinkedHashMap<>();
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

    private static String getCellValue(
            Row row, List<Integer> columnIndexes, DataFormatter formatter) {
        if (columnIndexes == null) return null;
        for (Integer columnIndex : columnIndexes) {
            String value = getCellValue(row, columnIndex, formatter);
            if (value != null) return value;
        }
        return null;
    }

    private static Map<String, List<Integer>> buildFieldIndexes(
            Map<String, Integer> headerIndex, Map<String, String> columnMappings) {
        Map<String, List<Integer>> fieldIndexes = new LinkedHashMap<>();
        for (Map.Entry<String, String> mapping : columnMappings.entrySet()) {
            Integer columnIndex = headerIndex.get(mapping.getKey());
            if (columnIndex != null) {
                fieldIndexes
                        .computeIfAbsent(mapping.getValue(), ignored -> new ArrayList<>())
                        .add(columnIndex);
            }
        }
        return fieldIndexes;
    }

    private static Set<String> mappedHeaders(
            Map<String, String> columnMappings, Set<String> semanticFields) {
        Set<String> headers = new LinkedHashSet<>();
        columnMappings.forEach((header, semanticField) -> {
            if (semanticFields.contains(semanticField)) headers.add(header);
        });
        return headers;
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

    static boolean isControlRow(
            String sampleId, String qcTask, ControlResultRecognition recognition) {
        return evaluateControl(sampleId, qcTask, recognition).matchedRule().isPresent();
    }

    static ControlResultRecognitionEvaluator.Assessment evaluateControl(
            String sampleId, String qcTask, ControlResultRecognition recognition) {
        Map<String, String> fieldValues = new HashMap<>();
        if (qcTask != null) {
            fieldValues.put("QC_TASK", qcTask);
        }
        return ControlResultRecognitionEvaluator.evaluate(
                recognition, sampleId, fieldValues);
    }
}
