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
import org.itech.ahb.profile.AstmResultParts;
import org.itech.ahb.profile.AstmResultRecordSelection;
import org.itech.ahb.profile.ControlRecognitionRule;
import org.itech.ahb.profile.ControlResultRecognition;
import org.itech.ahb.profile.ControlResultRecognitionEvaluator;
import org.itech.ahb.profile.ResultReading;

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
        return parse(lines, recognition, resultRecordSelection, null);
    }

    /**
     * Parse ASTM message lines, reading every part of each result record where the pinned
     * profile says it sits. A profile that declares no result parts is read as it always was.
     *
     * @param reading how the pinned profile reads a result; null for the established reading
     */
    public static HL7ResultParser.ParsedResults parse(
            List<String> lines, ControlResultRecognition recognition,
            AstmResultRecordSelection resultRecordSelection, ResultReading reading) {
        if (lines == null || lines.isEmpty()) return null;
        if (resultRecordSelection == null) {
            throw new IllegalArgumentException("ASTM result-record selection is required");
        }
        AstmResultParts parts = reading == null ? null : reading.astmParts();

        String accession = null;
        ControlResultRecognitionEvaluator.Assessment recognitionAssessment = null;
        List<AnalyzerResult> results = new ArrayList<>();
        InstrumentPatient patient = null;
        String specimenDescriptor = null;
        // The result a following C record belongs to; null after a record that said nothing.
        int commentTarget = -1;

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
                    if (parts != null) {
                        String descriptor = AstmResultParts.read(parts.specimenDescriptor(), fields);
                        specimenDescriptor = descriptor.isEmpty() ? null : descriptor;
                    }
                }
                case "P" -> {
                    if (parts != null) {
                        patient = readPatient(line, parts);
                    }
                }
                case "C" -> {
                    if (parts != null && commentTarget >= 0) {
                        String note = readNote(line, parts);
                        if (note != null) {
                            AnalyzerResult target = results.get(commentTarget);
                            results.set(commentTarget, target.withParts(target.parts().withNote(note)));
                        }
                    }
                }
                case "R" -> {
                    commentTarget = -1;
                    if (accession != null) {
                        AnalyzerResult result = parts == null
                                ? parseResultRecord(line, resultRecordSelection)
                                : parseResultRecord(line, resultRecordSelection, parts, reading);
                        if (result != null) {
                            result = result.withControlRecognition(recognitionAssessment);
                            if (recognitionAssessment.matchedRule().isPresent()) {
                                ControlRecognitionRule rule = recognitionAssessment.matchedRule().orElseThrow();
                                result = result.withControl(true)
                                        .withControlLevel(rule.controlLevel())
                                        .withControlType(rule.controlType());
                            }
                            results.add(result);
                            commentTarget = parts == null ? -1 : results.size() - 1;
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

        return results.isEmpty()
                ? null
                : new HL7ResultParser.ParsedResults(accession, results, patient, specimenDescriptor);
    }

    /**
     * Parse from raw ASTM message string (splits on newlines).
     */
    public static HL7ResultParser.ParsedResults parseRaw(
            String rawAstm, ControlResultRecognition recognition,
            AstmResultRecordSelection resultRecordSelection) {
        return parseRaw(rawAstm, recognition, resultRecordSelection, null);
    }

    public static HL7ResultParser.ParsedResults parseRaw(
            String rawAstm, ControlResultRecognition recognition,
            AstmResultRecordSelection resultRecordSelection, ResultReading reading) {
        if (rawAstm == null || rawAstm.isBlank()) return null;
        List<String> lines = new ArrayList<>();
        for (String line : rawAstm.split("\r")) {
            if (!line.isBlank()) lines.add(line.trim());
        }
        return parse(lines, recognition, resultRecordSelection, reading);
    }

    /** The patient a P record reports, where the profile says to read one; null when it reports none. */
    private static InstrumentPatient readPatient(String patientRecord, AstmResultParts parts) {
        String[] fields = patientRecord.split(Pattern.quote(FIELD_DELIMITER), -1);
        String identifier = AstmResultParts.read(parts.patientId(), fields);
        String name = AstmResultParts.read(parts.patientName(), fields);
        String[] components = name.split(Pattern.quote(COMPONENT_DELIMITER), -1);
        String family = components.length > 0 ? components[0].trim() : "";
        String given = components.length > 1 ? components[1].trim() : "";
        if (identifier.isEmpty() && family.isEmpty() && given.isEmpty()) {
            return null;
        }
        return new InstrumentPatient(
                identifier.isEmpty() ? null : identifier,
                family.isEmpty() ? null : family,
                given.isEmpty() ? null : given);
    }

    /**
     * The note or error a C record carries. Its comment text is id^code^description^details^time
     * (Cepheid 301-2002 Rev E 6.3.4.1.7); a comment of one component is the text itself.
     */
    private static String readNote(String commentRecord, AstmResultParts parts) {
        String[] fields = commentRecord.split(Pattern.quote(FIELD_DELIMITER), -1);
        String text = AstmResultParts.read(parts.note(), fields);
        if (text.isEmpty()) {
            return null;
        }
        String[] components = text.split(Pattern.quote(COMPONENT_DELIMITER), -1);
        if (components.length == 1) {
            return text;
        }
        String id = components[0].trim();
        String code = components.length > 1 ? components[1].trim() : "";
        String description = components.length > 2 ? components[2].trim() : "";
        String details = components.length > 3 ? components[3].trim() : "";
        String time = components.length > 4 ? astmTime(components[4].trim()) : null;
        StringBuilder note = new StringBuilder(id);
        if (!code.isEmpty()) {
            note.append(' ').append(code);
        }
        if (!description.isEmpty()) {
            note.append(": ").append(description);
        }
        if (!details.isEmpty()) {
            note.append(" (").append(details).append(')');
        }
        if (time != null) {
            note.append(" at ").append(time.substring(0, Math.min(time.length(), 19)));
        }
        return note.toString();
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
            int component = specimenId.indexOf(COMPONENT_DELIMITER);
            String accession = (component < 0 ? specimenId : specimenId.substring(0, component)).trim();
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
     * Parse one R record into every part the profile locates on it. A record with neither a
     * call nor a number says nothing, so it is not a result.
     */
    static AnalyzerResult parseResultRecord(
            String resultRecord, AstmResultRecordSelection resultRecordSelection, AstmResultParts parts,
            ResultReading reading) {
        if (!resultRecordSelection.includes(resultRecord)) {
            return null;
        }
        String[] fields = resultRecord.split(Pattern.quote(FIELD_DELIMITER), -1);
        String testCode = AstmResultParts.read(parts.testCode(), fields);
        String call = AstmResultParts.read(parts.call(), fields);
        String number = AstmResultParts.read(parts.number(), fields);
        if (testCode.isEmpty() || (call.isEmpty() && number.isEmpty())) {
            return null;
        }
        String assayName = AstmResultParts.read(parts.assayName(), fields);
        // A record that names its assay is the main result: its analyte field is a result name.
        String analyte = assayName.isEmpty() ? AstmResultParts.read(parts.analyte(), fields) : "";
        String complement = AstmResultParts.read(parts.complement(), fields);
        String subIdentity = complement.isEmpty() ? analyte : analyte + "&" + complement;
        String range = AstmResultParts.read(parts.range(), fields);
        String flag = AstmResultParts.read(parts.flag(), fields);
        boolean offScale = flag.equals("<") || flag.equals(">");
        String comparator = offScale ? flag : null;
        String limit = number.isEmpty() && offScale ? limitOf(range, flag) : null;
        String canonicalNumber = number.isEmpty() ? null : reading.canonicalNumber(number);
        String canonicalLimit = limit == null ? null : reading.canonicalNumber(limit);
        String operator = AstmResultParts.read(parts.operator(), fields);
        String version = AstmResultParts.read(parts.assayVersion(), fields);
        String instrument = AstmResultParts.read(parts.instrument(), fields);
        String status = AstmResultParts.read(parts.status(), fields);
        String units = AstmResultParts.read(parts.unit(), fields);
        String raw = number.isEmpty() ? call : number;
        RecordParts recordParts = new RecordParts(
                subIdentity,
                call.isEmpty() ? null : call,
                canonicalNumber,
                comparator,
                canonicalLimit,
                range.isEmpty() ? null : range,
                flag.isEmpty() || offScale ? List.of() : List.of(flag),
                assayName.isEmpty() ? null : assayName,
                version.isEmpty() ? null : version,
                operator.isEmpty() || operator.equals("<None>") ? null : operator,
                instrument.isEmpty() ? null : instrument,
                status.isEmpty() ? null : status,
                reading.isRunFailure(testCode, subIdentity, raw),
                List.of());
        boolean quantity = !number.isEmpty() || limit != null;
        AnalyzerResult result = quantity
                ? AnalyzerResult.numeric(testCode, testCode, raw, units.isEmpty() ? null : units)
                : AnalyzerResult.text(testCode, testCode, raw);
        String time = testTime(fields, parts);
        if (time != null) {
            result = result.withTimestamp(time);
        }
        return result.withParts(recordParts);
    }

    /** The range limit an off-scale flag points at: the lower limit of "40.00 to 10000000.00" for "<". */
    private static String limitOf(String range, String flag) {
        String[] limits = range.split("\\s+to\\s+", -1);
        String limit = flag.equals("<") ? limits[0] : limits[limits.length - 1];
        return limits.length == 2 && !limit.isBlank() ? limit.trim() : null;
    }

    /** The time the test was performed, from the completed part, else the started part. */
    private static String testTime(String[] resultFields, AstmResultParts parts) {
        for (AstmResultParts.Field part : new AstmResultParts.Field[] {parts.completed(), parts.started()}) {
            String raw = AstmResultParts.read(part, resultFields);
            String time = raw.isEmpty() ? null : astmTime(raw);
            if (time != null) {
                return time;
            }
        }
        return null;
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
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '^') {
            end--;
        }
        String cleaned = value.substring(0, end);
        if (cleaned.startsWith(COMPONENT_DELIMITER)) {
            cleaned = cleaned.substring(1);
        }
        return cleaned.trim();
    }

    private static boolean isNumericValue(String value) {
        return NumericValue.isBoundedDecimal(value);
    }
}
