package org.itech.ahb.fhir;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.itech.ahb.fhir.FhirBundleBuilder.AnalyzerResult;
import org.itech.ahb.qc.QcRule;
import org.itech.ahb.qc.QcRuleEvaluator;

/**
 * Extracts lab results from ASTM LIS2-A2 messages.
 *
 * <p>Ported from OE's {@code GenericASTMLineInserter} — same field extraction
 * logic for O-record (accession) and R-record (test/value/units), without the
 * OE-specific test mapping lookup.
 *
 * <p>ASTM record layout:
 * <ul>
 *   <li>O|seq|specimenId^loc|...|actionCode|... — O.2 = accession, O.12 = "Q" for QC</li>
 *   <li>R|seq|^^^testCode|value|units|... — R.2 = test, R.3 = value, R.4 = units</li>
 * </ul>
 */
@Slf4j
public class ASTMResultParser {

    private static final String FIELD_DELIMITER = "|";
    private static final String COMPONENT_DELIMITER = "^";
    private static final int O_SPECIMEN_ID_FIELD = 2;
    private static final int R_TEST_ID_FIELD = 2;
    private static final int R_VALUE_FIELD = 3;
    private static final int R_UNITS_FIELD = 4;
    private static final int R_TIMESTAMP_FIELD = 9;
    // ASTM LIS2-A2 §5.7: Order Record Action Code is field 12 (1-indexed).
    // In a 0-indexed split that includes the segment ID ("O") at index 0,
    // O.12 lands at array index 11. Pinned by the mindray-ba88a-result.txt
    // fixture (action code "A" at idx 11) and by the analyzer mock's
    // test_qc_message.py (Q at idx 11).
    private static final int O_ACTION_CODE_FIELD = 11;

    /**
     * Parse ASTM message lines and extract results.
     *
     * @param lines ASTM message lines (H, P, O, R, L segments)
     * @return parsed results with accession, or null if no results found
     */
    public static HL7ResultParser.ParsedResults parse(List<String> lines) {
        return parse(lines, null);
    }

    /**
     * Parse ASTM message lines with configurable QC rules.
     * Falls back to hardcoded O.12=="Q" when qcRules is null or empty.
     *
     * @param lines   ASTM message lines
     * @param qcRules FR-15 QC identification rules (null = use hardcoded fallback)
     * @return parsed results with accession, or null if no results found
     */
    public static HL7ResultParser.ParsedResults parse(List<String> lines, List<QcRule> qcRules) {
        if (lines == null || lines.isEmpty()) return null;

        String accession = null;
        boolean isQcSample = false;
        List<AnalyzerResult> results = new ArrayList<>();

        for (String line : lines) {
            if (line == null || line.isEmpty()) continue;

            String segment = getSegmentType(line);

            switch (segment) {
                case "O" -> {
                    accession = extractAccessionNumber(line);
                    if (qcRules != null && !qcRules.isEmpty()) {
                        // FR-15: rule-based QC detection
                        String[] fields = line.split(Pattern.quote(FIELD_DELIMITER));
                        Map<String, String> fieldValues = new HashMap<>();
                        for (int i = 0; i < fields.length; i++) {
                            fieldValues.put("O." + i, fields[i].trim());
                        }
                        isQcSample = QcRuleEvaluator.isQcSample(qcRules, accession, fieldValues);
                    } else {
                        // Fallback: hardcoded O.12 == "Q"
                        isQcSample = isQcSample(line);
                    }
                }
                case "R" -> {
                    if (accession != null) {
                        AnalyzerResult result = parseResultRecord(line);
                        if (result != null) {
                            if (isQcSample) {
                                result = result.withControl(true);
                            }
                            results.add(result);
                        }
                    }
                }
                case "Q" -> {
                    // Q-segment carries QC metadata: field_code^lot_number^level
                    // (per LIS2-A2 §5.10 + OE GenericASTM convention). Pair
                    // with the most-recent R-record so OE's
                    // QCResultProcessingService can resolve to the correct
                    // control lot without guessing from accession.
                    if (!results.isEmpty()) {
                        String[] qFields = line.split(Pattern.quote(FIELD_DELIMITER));
                        if (qFields.length > 2 && qFields[2] != null && !qFields[2].isBlank()) {
                            String[] components = qFields[2].split(Pattern.quote(COMPONENT_DELIMITER));
                            String lotNumber = components.length >= 2 ? components[1].trim() : null;
                            String controlLevel = components.length >= 3 ? components[2].trim() : null;
                            int lastIdx = results.size() - 1;
                            AnalyzerResult last = results.get(lastIdx);
                            if (lotNumber != null && !lotNumber.isEmpty()) {
                                last = last.withLotNumber(lotNumber);
                            }
                            if (controlLevel != null && !controlLevel.isEmpty()) {
                                last = last.withControlLevel(controlLevel);
                            }
                            results.set(lastIdx, last);
                        }
                    }
                }
            }
        }

        if (accession == null) accession = "ASTM-UNKNOWN";

        return results.isEmpty() ? null : new HL7ResultParser.ParsedResults(accession, results);
    }

    /**
     * Parse from raw ASTM message string (splits on newlines).
     */
    public static HL7ResultParser.ParsedResults parseRaw(String rawAstm) {
        if (rawAstm == null || rawAstm.isBlank()) return null;
        List<String> lines = new ArrayList<>();
        for (String line : rawAstm.split("\r")) {
            if (!line.isBlank()) lines.add(line.trim());
        }
        return parse(lines);
    }

    /**
     * Extract segment type identifier.
     * Ported from GenericASTMLineInserter.getSegmentType().
     */
    private static String getSegmentType(String line) {
        int pos = line.indexOf(FIELD_DELIMITER);
        if (pos > 0) return line.substring(0, pos);
        return line.length() > 0 ? line.substring(0, 1) : "";
    }

    /**
     * Extract accession number from O-record.
     * Ported from GenericASTMLineInserter.extractAccessionNumber().
     *
     * O|seq|specimenId^location|...
     *       [2]
     */
    private static String extractAccessionNumber(String orderRecord) {
        String[] fields = orderRecord.split(Pattern.quote(FIELD_DELIMITER));
        if (fields.length > O_SPECIMEN_ID_FIELD) {
            String specimenId = fields[O_SPECIMEN_ID_FIELD];
            String[] components = specimenId.split(Pattern.quote(COMPONENT_DELIMITER));
            String accession = components[0].trim();
            return accession.isEmpty() ? null : accession;
        }
        return null;
    }

    /**
     * Parse one R-record to extract a test result.
     * Only Main Result records are forwarded as clinical results.
     * Per Cepheid LIS spec 302-2261 Rev C: Main Result has Component 5
     * (assay name) non-empty in the Universal Test ID field.
     * Analyte, Complementary, and internal control rows are skipped.
     *
     * R|seq|^^^testCode|value|units|...
     *       [2]         [3]   [4]
     */
    static AnalyzerResult parseResultRecord(String resultRecord) {
        String[] fields = resultRecord.split(Pattern.quote(FIELD_DELIMITER));

        if (!isMainResult(fields)) {
            return null;
        }

        String testCode = extractTestCode(fields);
        if (testCode == null || testCode.isEmpty()) return null;

        String cartridgeCode = extractCartridgeCode(fields);
        if (cartridgeCode != null) {
            log.trace("Multi-result cartridge={} analyte={}", cartridgeCode, testCode);
        }

        String value = cleanResultValue(
                fields.length > R_VALUE_FIELD ? fields[R_VALUE_FIELD] : "");
        if (value == null || value.isEmpty()) return null;

        String units = fields.length > R_UNITS_FIELD ? fields[R_UNITS_FIELD].trim() : "";
        String timestamp = fields.length > R_TIMESTAMP_FIELD ? fields[R_TIMESTAMP_FIELD].trim() : null;

        boolean isNumeric = isNumericValue(value);
        AnalyzerResult result = isNumeric
                ? AnalyzerResult.numeric(testCode, testCode, value, units)
                : AnalyzerResult.text(testCode, testCode, value);

        if (timestamp != null && !timestamp.isEmpty()) {
            result = result.withTimestamp(timestamp);
        }
        return result;
    }

    /**
     * Check if an R-record is a Main Result (clinical value).
     * Per Cepheid spec: Component 5 (index 4) of the Universal Test ID field
     * contains the assay name for Main Results and is empty for Analyte/Complementary rows.
     * For simple formats with fewer than 5 components, treat as Main Result (backward compat).
     */
    static boolean isMainResult(String[] fields) {
        if (fields.length <= R_TEST_ID_FIELD) return false;
        String testIdField = fields[R_TEST_ID_FIELD];
        String[] components = testIdField.split(Pattern.quote(COMPONENT_DELIMITER), -1);
        if (components.length < 5) return true;
        return !components[4].trim().isEmpty();
    }

    /**
     * Extract cartridge/panel code from Component 2 (index 1) of the Universal Test ID.
     * Present in multi-result tests (e.g. "CTNG" for CT/NG cartridge).
     */
    static String extractCartridgeCode(String[] fields) {
        if (fields.length <= R_TEST_ID_FIELD) return null;
        String testIdField = fields[R_TEST_ID_FIELD];
        String[] components = testIdField.split(Pattern.quote(COMPONENT_DELIMITER), -1);
        if (components.length >= 2 && !components[1].trim().isEmpty()) {
            return components[1].trim();
        }
        return null;
    }

    /**
     * Check if O-record indicates a QC sample via Action Code (O.12).
     * Ported from GenericASTMLineInserter.isQcSample().
     */
    private static boolean isQcSample(String orderRecord) {
        String[] fields = orderRecord.split(Pattern.quote(FIELD_DELIMITER));
        if (fields.length > O_ACTION_CODE_FIELD) {
            return "Q".equalsIgnoreCase(fields[O_ACTION_CODE_FIELD].trim());
        }
        return false;
    }

    /**
     * Extract test code from R-record Universal Test ID field.
     * Ported from GenericASTMLineInserter.extractTestCode().
     *
     * Formats: ^^^TEST_CODE (component 4) or ^TEST_CODE (component 2) or plain TEST_CODE
     */
    private static String extractTestCode(String[] resultFields) {
        if (resultFields.length <= R_TEST_ID_FIELD) return null;
        String testIdField = resultFields[R_TEST_ID_FIELD];
        String[] components = testIdField.split(Pattern.quote(COMPONENT_DELIMITER), -1);

        // Standard: ^^^TEST_CODE (4th component)
        if (components.length >= 4 && !components[3].trim().isEmpty()) {
            return components[3].trim();
        }
        // Short: ^TEST_CODE (2nd component)
        if (components.length >= 2 && !components[1].trim().isEmpty()) {
            return components[1].trim();
        }
        // Fallback: whole field
        if (components.length == 1 && !testIdField.trim().isEmpty()) {
            return testIdField.trim();
        }
        return null;
    }

    /**
     * Clean ASTM result value by stripping component delimiters.
     * Ported from GenericASTMLineInserter.cleanResultValue().
     *
     * Handles: "NEGATIVE^" → "NEGATIVE", "^3.10" → "3.10"
     */
    private static String cleanResultValue(String value) {
        if (value == null || value.isEmpty()) return value;
        String cleaned = value.replaceAll("\\^+$", "");
        if (cleaned.startsWith(COMPONENT_DELIMITER)) {
            cleaned = cleaned.substring(1);
        }
        return cleaned.trim();
    }

    private static boolean isNumericValue(String value) {
        try {
            Double.parseDouble(value.replaceAll("[<>]", ""));
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
