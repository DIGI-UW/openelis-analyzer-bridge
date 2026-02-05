package org.itech.ahb.file;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CSV parser with support for default and custom column mappings.
 * <p>
 * Provides standard CSV parsing with configurable column mappings per analyzer.
 * Falls back to default mapping (SampleID, TestCode, Result, Units) if no custom
 * mapping is configured.
 * </p>
 */
@Component
@ConditionalOnProperty(prefix = "bridge.file", name = "enabled", havingValue = "true")
@Slf4j
public class CSVParser {

    /**
     * Default CSV column mapping indices (zero-based)
     */
    public static final String FIELD_SAMPLE_ID = "sampleId";
    public static final String FIELD_TEST_CODE = "testCode";
    public static final String FIELD_RESULT = "result";
    public static final String FIELD_UNITS = "units";
    public static final String FIELD_TIMESTAMP = "timestamp";
    public static final String FIELD_FLAGS = "flags";

    /**
     * Default column indices for standard CSV format
     * Expected format: SampleID, TestCode, Result, Units, Timestamp, Flags
     */
    private static final Map<String, Integer> DEFAULT_MAPPING = Map.of(
            FIELD_SAMPLE_ID, 0,
            FIELD_TEST_CODE, 1,
            FIELD_RESULT, 2,
            FIELD_UNITS, 3,
            FIELD_TIMESTAMP, 4,
            FIELD_FLAGS, 5
    );

    private final FileConfig fileConfig;

    public CSVParser(FileConfig fileConfig) {
        this.fileConfig = fileConfig;
    }

    /**
     * Parse CSV content using analyzer-specific or default mapping.
     *
     * @param csvContent  the CSV content as string
     * @param analyzerId  the analyzer identifier (for custom mapping lookup)
     * @return list of parsed CSV records as field maps
     * @throws IOException if CSV parsing fails
     */
    public List<Map<String, String>> parse(String csvContent, String analyzerId) throws IOException {
        Map<String, Integer> columnMapping = getColumnMapping(analyzerId);

        List<Map<String, String>> parsedRecords = new ArrayList<>();

        try (Reader reader = new StringReader(csvContent)) {
            // Use Apache Commons CSV with Excel format (handles quoted fields, escapes)
            Iterable<CSVRecord> records = CSVFormat.DEFAULT
                    .builder()
                    .setHeader()
                    .setSkipHeaderRecord(true)
                    .setIgnoreEmptyLines(true)
                    .setTrim(true)
                    .build()
                    .parse(reader);

            for (CSVRecord record : records) {
                Map<String, String> parsedRow = extractFields(record, columnMapping);
                if (parsedRow != null && !parsedRow.isEmpty()) {
                    parsedRecords.add(parsedRow);
                }
            }
        }

        log.debug("Parsed {} CSV records for analyzer {}", parsedRecords.size(), analyzerId);
        return parsedRecords;
    }

    /**
     * Parse CSV content with auto-detected headers.
     * Uses first row as header to determine column names.
     *
     * @param csvContent the CSV content as string
     * @return list of parsed CSV records with all columns preserved
     * @throws IOException if CSV parsing fails
     */
    public List<Map<String, String>> parseWithHeaders(String csvContent) throws IOException {
        List<Map<String, String>> parsedRecords = new ArrayList<>();

        try (Reader reader = new StringReader(csvContent)) {
            Iterable<CSVRecord> records = CSVFormat.DEFAULT
                    .builder()
                    .setHeader()
                    .setSkipHeaderRecord(true)
                    .setIgnoreEmptyLines(true)
                    .setTrim(true)
                    .build()
                    .parse(reader);

            for (CSVRecord record : records) {
                Map<String, String> row = new HashMap<>();
                record.toMap().forEach((key, value) -> {
                    if (value != null && !value.trim().isEmpty()) {
                        row.put(key.toLowerCase(), value.trim());
                    }
                });

                if (!row.isEmpty()) {
                    parsedRecords.add(row);
                }
            }
        }

        log.debug("Parsed {} CSV records with headers", parsedRecords.size());
        return parsedRecords;
    }

    /**
     * Validate CSV content has minimum required structure.
     *
     * @param csvContent the CSV content to validate
     * @return true if CSV appears valid (has rows and columns)
     */
    public boolean isValidCSV(String csvContent) {
        if (csvContent == null || csvContent.trim().isEmpty()) {
            return false;
        }

        try (Reader reader = new StringReader(csvContent)) {
            Iterable<CSVRecord> records = CSVFormat.DEFAULT
                    .builder()
                    .setIgnoreEmptyLines(true)
                    .build()
                    .parse(reader);

            int rowCount = 0;
            for (CSVRecord record : records) {
                if (record.size() < 2) {  // At least 2 columns required
                    return false;
                }
                rowCount++;
                if (rowCount >= 2) {  // At least header + 1 data row
                    return true;
                }
            }

            return rowCount >= 2;
        } catch (IOException e) {
            log.warn("CSV validation failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Get column mapping for analyzer (custom or default).
     *
     * @param analyzerId the analyzer identifier
     * @return column mapping (field name to column index)
     */
    private Map<String, Integer> getColumnMapping(String analyzerId) {
        if (analyzerId != null && fileConfig.hasCustomCsvMapping(analyzerId)) {
            Map<String, Integer> customMapping = fileConfig.getCsvMappingForAnalyzer(analyzerId);
            log.debug("Using custom CSV mapping for analyzer {}: {}", analyzerId, customMapping);
            return customMapping;
        }

        log.debug("Using default CSV mapping for analyzer {}", analyzerId);
        return DEFAULT_MAPPING;
    }

    /**
     * Extract fields from CSV record using column mapping.
     *
     * @param record        the CSV record
     * @param columnMapping the column mapping (field name to index)
     * @return map of field name to value
     */
    private Map<String, String> extractFields(CSVRecord record, Map<String, Integer> columnMapping) {
        Map<String, String> fields = new HashMap<>();

        for (Map.Entry<String, Integer> entry : columnMapping.entrySet()) {
            String fieldName = entry.getKey();
            Integer columnIndex = entry.getValue();

            if (columnIndex != null && columnIndex >= 0 && columnIndex < record.size()) {
                String value = record.get(columnIndex);
                if (value != null && !value.trim().isEmpty()) {
                    fields.put(fieldName, value.trim());
                }
            }
        }

        return fields;
    }

    /**
     * Convert parsed records back to CSV format for forwarding to OpenELIS.
     *
     * @param records list of parsed record maps
     * @return CSV string with headers
     */
    public String toCsvString(List<Map<String, String>> records) {
        if (records == null || records.isEmpty()) {
            return "";
        }

        StringBuilder csv = new StringBuilder();

        // Build header from first record keys
        Map<String, String> firstRecord = records.get(0);
        csv.append(String.join(",", firstRecord.keySet())).append("\n");

        // Build data rows
        for (Map<String, String> record : records) {
            List<String> values = new ArrayList<>();
            for (String key : firstRecord.keySet()) {
                String value = record.getOrDefault(key, "");
                // Escape commas and quotes in values
                if (value.contains(",") || value.contains("\"")) {
                    value = "\"" + value.replace("\"", "\"\"") + "\"";
                }
                values.add(value);
            }
            csv.append(String.join(",", values)).append("\n");
        }

        return csv.toString();
    }
}
