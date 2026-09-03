package org.itech.ahb.fhir;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.itech.ahb.fhir.FhirBundleBuilder.AnalyzerResult;
import org.itech.ahb.profile.ControlRecognitionRule;
import org.itech.ahb.profile.ControlResultRecognition;
import org.itech.ahb.profile.ControlResultRecognitionEvaluator;

/**
 * Extracts lab results from HL7 v2 ORU^R01 messages.
 *
 * <p>Ported from OE's {@code GenericHL7LineInserter.parseObxSegment()} and
 * {@code parseAccessionFromOBR()} — same field extraction logic, without the
 * OE-specific test mapping lookup (bridge doesn't need it; OE maps test codes
 * when it receives the FHIR Bundle).
 *
 * <p>OBR field layout: OBR|seq|placer|filler|panel|...
 * <p>OBX field layout: OBX|seq|valueType|testCode|subId|value|units|refRange|flag|...
 */
@Slf4j
public class HL7ResultParser {

    /**
     * Parse HL7 v2 segment lines using the pinned profile's explicit control
     * recognition mode.
     * @param segmentLines list of HL7 segment strings
     * @param recognition profile-owned control-result recognition
     * @return parsed results with accession, or null if no results found
     */
    public static ParsedResults parse(
            List<String> segmentLines, ControlResultRecognition recognition) {
        if (segmentLines == null || segmentLines.isEmpty()) {
            return null;
        }

        String accession = null;
        Map<String, String> fieldValues = new HashMap<>();
        List<AnalyzerResult> results = new ArrayList<>();

        for (String line : segmentLines) {
            if (line == null) continue;

            if (line.startsWith("OBR|")) {
                accession = parseAccessionFromOBR(line);
                // Extract OBR fields for rule evaluation
                String[] fields = line.split("\\|", -1);
                for (int i = 0; i < fields.length; i++) {
                    fieldValues.put("OBR." + i, fields[i].trim());
                }
            }

            if (line.startsWith("PID|")) {
                // Extract PID fields for rule evaluation
                String[] fields = line.split("\\|", -1);
                for (int i = 0; i < fields.length; i++) {
                    fieldValues.put("PID." + i, fields[i].trim());
                }
            }

            if (line.startsWith("OBX|")) {
                AnalyzerResult result = parseObxSegment(line);
                if (result != null) {
                    results.add(result);
                }
            }
        }

        if (accession == null || accession.isBlank()) {
            // Fallback: try PID-3 patient ID
            String pid3 = fieldValues.get("PID.3");
            if (pid3 != null && !pid3.isBlank()) {
                accession = pid3.split("\\^")[0].trim();
            }
        }

        if (accession == null) accession = "HL7-UNKNOWN";

        ControlResultRecognitionEvaluator.Assessment assessment =
                ControlResultRecognitionEvaluator.evaluate(recognition, accession, fieldValues);
        ControlRecognitionRule matchedRule = assessment.matchedRule().orElse(null);
        results = results.stream()
                .map(result -> {
                    AnalyzerResult assessed = result.withControlRecognition(assessment);
                    return matchedRule == null ? assessed : assessed.withControl(true)
                            .withControlLevel(matchedRule.controlLevel())
                            .withControlType(matchedRule.controlType());
                })
                .toList();

        return results.isEmpty() ? null : new ParsedResults(accession, results);
    }

    /**
     * Parse HL7 from raw message string (splits on segment terminators first).
     */
    public static ParsedResults parseRaw(
            String rawHl7, ControlResultRecognition recognition) {
        if (rawHl7 == null || rawHl7.isBlank()) return null;
        // Normalize terminators: \r\n → \r, \n → \r, then split
        String normalized = rawHl7.replace("\r\n", "\r").replace("\n", "\r");
        List<String> segments = new ArrayList<>();
        for (String seg : normalized.split("\r")) {
            if (!seg.isBlank()) segments.add(seg);
        }
        return parse(segments, recognition);
    }

    /**
     * Parse accession from OBR segment.
     * Ported from GenericHL7LineInserter.parseAccessionFromOBR().
     *
     * OBR|seq|placer|filler|panel...
     * Try filler (field 3) first, fall back to placer (field 2).
     */
    private static String parseAccessionFromOBR(String obrLine) {
        String[] fields = obrLine.split("\\|", -1);
        if (fields.length > 3 && !fields[3].isBlank()) {
            return fields[3].trim();
        }
        if (fields.length > 2 && !fields[2].isBlank()) {
            return fields[2].trim();
        }
        return null;
    }

    /**
     * Parse one OBX segment to extract a test result.
     * Ported from GenericHL7LineInserter.parseObxSegment().
     *
     * OBX|seq|valueType|testCode|subId|value|units|refRange|flag...
     *     [1]   [2]      [3]     [4]   [5]   [6]
     */
    private static AnalyzerResult parseObxSegment(String obxLine) {
        String[] fields = obxLine.split("\\|", -1);
        if (fields.length < 6) return null;

        String testCode = extractTestCode(fields[3]);
        if (testCode == null || testCode.isBlank()) return null;

        String value = fields.length > 5 ? fields[5].trim() : "";
        if (value.isBlank()) return null;

        String units = "";
        if (fields.length > 6 && !fields[6].isBlank()) {
            units = fields[6].split("\\^")[0].trim();
        }

        String valueType = fields.length > 2 ? fields[2].trim() : "";
        boolean isNumeric = "NM".equals(valueType) || "SN".equals(valueType);

        // Use test code as both code and name (OE will map via AnalyzerTestNameCache)
        return isNumeric
                ? AnalyzerResult.numeric(testCode, testCode, value, units)
                : AnalyzerResult.text(testCode, testCode, value);
    }

    /**
     * Extract test code from OBX-3 CE (Coded Element) field.
     * Ported from GenericHL7LineInserter.extractTestCode().
     *
     * Handles two formats:
     * - Simple: "WBC" (component 1)
     * - Complex: "^^^WBC^WHITE BLOOD CELL" (component 4)
     */
    private static String extractTestCode(String obx3Field) {
        if (obx3Field == null || obx3Field.isBlank()) return null;

        String[] components = obx3Field.split("\\^", -1);

        // Strategy 1: component 1 (simple format)
        if (components.length > 0 && !components[0].isBlank()) {
            return components[0].trim();
        }
        // Strategy 2: component 4 (complex format with empty leading components)
        if (components.length >= 4 && !components[3].isBlank()) {
            return components[3].trim();
        }
        // Strategy 3: last non-empty component
        for (int i = components.length - 1; i >= 0; i--) {
            if (!components[i].isBlank()) return components[i].trim();
        }
        return null;
    }

    public record ParsedResults(String accessionNumber, List<AnalyzerResult> results) {}
}
