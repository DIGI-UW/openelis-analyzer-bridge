package org.itech.ahb.fhir;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.itech.ahb.fhir.FhirBundleBuilder.AnalyzerResult;
import org.itech.ahb.profile.AstmResultRecordSelection;
import org.itech.ahb.profile.ControlRecognitionRule;
import org.itech.ahb.profile.ControlResultRecognition;
import org.itech.ahb.profile.ControlResultRecognitionEvaluator;

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
    private static final int R_STARTED_FIELD = 11;
    private static final int R_COMPLETED_FIELD = 12;
    private static final DateTimeFormatter ASTM_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter ASTM_DATE_TIME_MINUTES = DateTimeFormatter.ofPattern("yyyyMMddHHmm");
    private static final DateTimeFormatter ASTM_DATE_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    /** FHIR dateTime: seconds are required whenever a time is present. */
    private static final DateTimeFormatter FHIR_DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");
    /**
     * Parse ASTM message lines using the pinned profile's explicit control
     * recognition mode.
     *
     * @param lines ASTM message lines
     * @param recognition profile-owned control-result recognition
     * @return parsed results with accession, or null if no results found
     */
    public static HL7ResultParser.ParsedResults parse(
            List<String> lines, ControlResultRecognition recognition,
            AstmResultRecordSelection resultRecordSelection) {
        if (lines == null || lines.isEmpty()) return null;
        if (resultRecordSelection == null) {
            throw new IllegalArgumentException("ASTM result-record selection is required");
        }

        String accession = null;
        ControlResultRecognitionEvaluator.Assessment recognitionAssessment = null;
        List<AnalyzerResult> results = new ArrayList<>();

        for (String line : lines) {
            if (line == null || line.isEmpty()) continue;

            String segment = getSegmentType(line);

            switch (segment) {
                case "O" -> {
                    accession = extractAccessionNumber(line);
                    String[] fields = line.split(Pattern.quote(FIELD_DELIMITER));
                    Map<String, String> fieldValues = new HashMap<>();
                    for (int i = 0; i < fields.length; i++) {
                        fieldValues.put("O." + (i + 1), fields[i].trim());
                    }
                    recognitionAssessment = ControlResultRecognitionEvaluator.evaluate(
                            recognition, accession, fieldValues);
                }
                case "R" -> {
                    if (accession != null) {
                        AnalyzerResult result = parseResultRecord(line, resultRecordSelection);
                        if (result != null) {
                            result = result.withControlRecognition(recognitionAssessment);
                            if (recognitionAssessment.matchedRule().isPresent()) {
                                ControlRecognitionRule rule = recognitionAssessment.matchedRule().orElseThrow();
                                result = result.withControl(true)
                                        .withControlLevel(rule.controlLevel())
                                        .withControlType(rule.controlType());
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
    public static HL7ResultParser.ParsedResults parseRaw(
            String rawAstm, ControlResultRecognition recognition,
            AstmResultRecordSelection resultRecordSelection) {
        if (rawAstm == null || rawAstm.isBlank()) return null;
        List<String> lines = new ArrayList<>();
        for (String line : rawAstm.split("\r")) {
            if (!line.isBlank()) lines.add(line.trim());
        }
        return parse(lines, recognition, resultRecordSelection);
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
     * R|seq|^^^testCode|value|units|...
     *       [2]         [3]   [4]
     */
    static AnalyzerResult parseResultRecord(
            String resultRecord, AstmResultRecordSelection resultRecordSelection) {
        String[] fields = resultRecord.split(Pattern.quote(FIELD_DELIMITER));

        if (!resultRecordSelection.includes(resultRecord)) {
            return null;
        }

        String testCode = extractTestCode(fields);
        if (testCode == null || testCode.isEmpty()) return null;

        String value = cleanResultValue(
                fields.length > R_VALUE_FIELD ? fields[R_VALUE_FIELD] : "");
        if (value == null || value.isEmpty()) return null;

        String units = fields.length > R_UNITS_FIELD ? fields[R_UNITS_FIELD].trim() : "";
        String timestamp = testTime(fields);

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
     * The time the test was performed, as an ISO-8601 date or date-time: the first readable of R.13
     * "date/time test completed" and R.12 "date/time test started". ASTM times carry no offset, so
     * they are read in the JVM's zone, which follows the container's {@code TZ}.
     */
    private static String testTime(String[] resultFields) {
        for (int index : new int[] {R_COMPLETED_FIELD, R_STARTED_FIELD}) {
            String raw = field(resultFields, index);
            String time = raw.isEmpty() ? null : astmTime(raw);
            if (time != null) {
                return time;
            }
        }
        return null;
    }

    /** LIS2-A2 dates are YYYYMMDDHHMMSS, truncated to the precision the instrument knows. */
    private static String astmTime(String raw) {
        ZoneId zone = ZoneId.systemDefault();
        String time;
        try {
            time = switch (raw.length()) {
                case 8 -> LocalDate.parse(raw, ASTM_DATE).toString();
                case 12 -> LocalDateTime.parse(raw, ASTM_DATE_TIME_MINUTES).atZone(zone).format(FHIR_DATE_TIME);
                case 14 -> LocalDateTime.parse(raw, ASTM_DATE_TIME).atZone(zone).format(FHIR_DATE_TIME);
                default -> null;
            };
        } catch (DateTimeParseException e) {
            time = null;
        }
        if (time == null) {
            log.warn("Ignoring unreadable ASTM test time '{}'", raw);
        }
        return time;
    }

    private static String field(String[] fields, int index) {
        return fields.length > index ? fields[index].trim() : "";
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
