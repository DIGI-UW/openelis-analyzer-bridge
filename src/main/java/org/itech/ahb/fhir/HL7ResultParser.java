package org.itech.ahb.fhir;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.itech.ahb.fhir.FhirBundleBuilder.AnalyzerResult;
import org.itech.ahb.profile.ControlRecognitionRule;
import org.itech.ahb.profile.ControlResultRecognition;
import org.itech.ahb.profile.ControlResultRecognitionEvaluator;
import org.itech.ahb.profile.Hl7SpecimenPosition;

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

  /** Sending Application component 1, using the separators declared by the retained message. */
  public static String sendingApplication(String rawMessage) {
    if (rawMessage == null) return null;
    String header = rawMessage.stripLeading().split("[\\r\\n]", 2)[0];
    if (!header.startsWith("MSH") || header.length() < 8) return null;
    Delimiters delimiters = new Delimiters(header.charAt(3), header.charAt(4), header.charAt(5), header.charAt(7));
    Map<String, String> fields = new HashMap<>();
    extractRecognitionFields(header, "MSH", delimiters, fields);
    String sender = fields.get("MSH.3.1");
    return sender == null || sender.isBlank() ? null : sender;
  }

  /**
   * Parse HL7 v2 segment lines using the pinned profile's explicit control
   * recognition mode.
   * @param segmentLines list of HL7 segment strings
   * @param recognition profile-owned control-result recognition
   * @return parsed results with accession, or null if no results found
   */
  public static ParsedResults parse(List<String> segmentLines, ControlResultRecognition recognition) {
    return parse(segmentLines, recognition, Hl7SpecimenPosition.PRECEDING);
  }

  /** FOLLOWING_OBX binds only the following SPM to an observation; other evidence is captured at OBX. */
  public static ParsedResults parse(
    List<String> segmentLines,
    ControlResultRecognition recognition,
    Hl7SpecimenPosition specimenPosition
  ) {
    java.util.Objects.requireNonNull(specimenPosition, "specimenPosition");
    if (segmentLines == null || segmentLines.isEmpty()) {
      return null;
    }

    String accession = null;
    Map<String, String> fieldValues = new HashMap<>();
    List<AnalyzerResult> results = new ArrayList<>();
    Delimiters delimiters = new Delimiters('|', '^', '~', '&');
    PendingObservation pending = null;

    for (String line : segmentLines) {
      if (line == null || line.length() < 4) continue;
      String segment = line.substring(0, 3);
      if (pending != null && List.of("OBX", "OBR", "ORC", "PID", "MSH").contains(segment)) {
        results.add(recognize(pending.result(), pending.specimenId(), pending.fields(), recognition));
        pending = null;
      }
      if (specimenPosition == Hl7SpecimenPosition.FOLLOWING_OBX && "OBX".equals(segment)) {
        fieldValues.keySet().removeIf(key -> key.startsWith("SPM."));
      }
      if ("MSH".equals(segment) && line.length() >= 8) {
        delimiters = new Delimiters(line.charAt(3), line.charAt(4), line.charAt(5), line.charAt(7));
      }
      if (line.charAt(3) != delimiters.field()) continue;
      extractRecognitionFields(line, segment, delimiters, fieldValues);
      if (pending != null && "SPM".equals(segment)) {
        extractRecognitionFields(line, segment, delimiters, pending.fields());
      }

      if ("OBR".equals(segment)) {
        accession = parseAccessionFromOBR(line, delimiters);
      }

      if ("OBX".equals(segment)) {
        AnalyzerResult result = parseObxSegment(line, delimiters);
        if (result != null) {
          String specimenId = actualAccession(accession, fieldValues, delimiters);
          if (specimenPosition == Hl7SpecimenPosition.FOLLOWING_OBX) {
            pending = new PendingObservation(result, specimenId, new HashMap<>(fieldValues));
          } else {
            results.add(recognize(result, specimenId, fieldValues, recognition));
          }
        }
      }
    }

    if (pending != null) {
      results.add(recognize(pending.result(), pending.specimenId(), pending.fields(), recognition));
    }
    accession = actualAccession(accession, fieldValues, delimiters);
    // Recognition already used instrument evidence, never this display-only fallback.
    if (accession == null) accession = "HL7-UNKNOWN";

    return results.isEmpty() ? null : new ParsedResults(accession, results);
  }

  private record PendingObservation(AnalyzerResult result, String specimenId, Map<String, String> fields) {}

  private static AnalyzerResult recognize(
    AnalyzerResult result,
    String specimenId,
    Map<String, String> fields,
    ControlResultRecognition recognition
  ) {
    var assessment = ControlResultRecognitionEvaluator.evaluate(recognition, specimenId, fields);
    result = result.withControlRecognition(assessment);
    if (assessment.matchedRule().isPresent()) {
      ControlRecognitionRule rule = assessment.matchedRule().orElseThrow();
      result = result.withControl(true).withControlLevel(rule.controlLevel()).withControlType(rule.controlType());
    }
    return result;
  }

  private static String actualAccession(String accession, Map<String, String> fields, Delimiters delimiters) {
    if (accession != null && !accession.isBlank()) return accession;
    String patientId = fields.get("PID.3");
    if (patientId == null) return null;
    String identifier = split(split(patientId, delimiters.repetition())[0], delimiters.component())[0].trim();
    return identifier.isBlank() ? null : identifier;
  }

  /**
   * Replace the segment's prior fields, including absent trailing components.
   * Recognition captures each OBX, so later observations/orders cannot rewrite
   * earlier results. Whole fields retain raw repetitions; component references
   * select the first repetition, as the profile path has no repetition index.
   */
  private static void extractRecognitionFields(
    String line,
    String segment,
    Delimiters delimiters,
    Map<String, String> values
  ) {
    String prefix = segment + ".";
    values.keySet().removeIf(key -> key.startsWith(prefix));
    String[] fields = split(line, delimiters.field());
    boolean header = "MSH".equals(segment);
    if (header) values.put("MSH.1", String.valueOf(delimiters.field()));
    for (int i = 1; i < fields.length; i++) {
      String path = prefix + (header ? i + 1 : i);
      values.put(path, fields[i].trim());
      // MSH-2 declares separators; they are not component evidence.
      if (header && i == 1) continue;
      String[] components = split(split(fields[i], delimiters.repetition())[0], delimiters.component());
      for (int component = 0; component < components.length; component++) {
        String componentPath = path + "." + (component + 1);
        values.put(componentPath, components[component].trim());
        String[] subcomponents = split(components[component], delimiters.subcomponent());
        for (int subcomponent = 0; subcomponent < subcomponents.length; subcomponent++) {
          values.put(componentPath + "." + (subcomponent + 1), subcomponents[subcomponent].trim());
        }
      }
    }
  }

  private static String[] split(String value, char delimiter) {
    return value.split(Pattern.quote(String.valueOf(delimiter)), -1);
  }

  private record Delimiters(char field, char component, char repetition, char subcomponent) {}

  /**
   * Parse HL7 from raw message string (splits on segment terminators first).
   */
  public static ParsedResults parseRaw(String rawHl7, ControlResultRecognition recognition) {
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
  private static String parseAccessionFromOBR(String obrLine, Delimiters delimiters) {
    String[] fields = split(obrLine, delimiters.field());
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
  private static AnalyzerResult parseObxSegment(String obxLine, Delimiters delimiters) {
    String[] fields = split(obxLine, delimiters.field());
    if (fields.length < 6) return null;

    String testCode = extractTestCode(fields[3], delimiters);
    if (testCode == null || testCode.isBlank()) return null;

    String value = fields.length > 5 ? fields[5].trim() : "";
    if (value.isBlank()) return null;

    String units = "";
    if (fields.length > 6 && !fields[6].isBlank()) {
      units = split(fields[6], delimiters.component())[0].trim();
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
  private static String extractTestCode(String obx3Field, Delimiters delimiters) {
    if (obx3Field == null || obx3Field.isBlank()) return null;

    String[] components = split(obx3Field, delimiters.component());

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
