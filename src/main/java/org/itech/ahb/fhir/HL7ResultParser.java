package org.itech.ahb.fhir;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.itech.ahb.fhir.FhirBundleBuilder.AnalyzerResult;
import org.itech.ahb.profile.ControlRecognitionRule;
import org.itech.ahb.profile.ControlResultRecognition;
import org.itech.ahb.profile.ControlResultRecognitionEvaluator;
import org.itech.ahb.profile.Hl7ResultParts;
import org.itech.ahb.profile.Hl7ResultRecordSelection;
import org.itech.ahb.profile.Hl7SpecimenPosition;
import org.itech.ahb.profile.ResultReading;
import org.itech.ahb.profile.SampleFlags;

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
    Map<String, String> fields = extractRecognitionFields(header, "MSH", delimiters);
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
    return parse(segmentLines, recognition, specimenPosition, Hl7ResultRecordSelection.all(), null);
  }

  /**
   * Parse HL7 v2 segment lines, reading every part of each observation the profile selects where
   * the pinned profile says it sits. A profile that declares no result parts is read as it always
   * was.
   *
   * @param reading how the pinned profile reads a result; null for the established reading
   */
  public static ParsedResults parse(
    List<String> segmentLines,
    ControlResultRecognition recognition,
    Hl7SpecimenPosition specimenPosition,
    Hl7ResultRecordSelection resultRecordSelection,
    ResultReading reading
  ) {
    Objects.requireNonNull(specimenPosition, "specimenPosition");
    Objects.requireNonNull(resultRecordSelection, "resultRecordSelection");
    if (segmentLines == null || segmentLines.isEmpty()) {
      return null;
    }
    Hl7ResultParts parts = reading == null ? null : reading.hl7Parts();

    String accession = null;
    SegmentFields fieldValues = new SegmentFields();
    List<AnalyzerResult> results = new ArrayList<>();
    List<SampleFlag> sampleFlags = new ArrayList<>();
    Delimiters delimiters = new Delimiters('|', '^', '~', '&');
    PendingObservation pending = null;
    InstrumentPatient patient = null;
    String specimenDescriptor = null;
    // The result a following NTE belongs to; -1 when the last observation was not one.
    int noteTarget = -1;

    for (String line : segmentLines) {
      if (line == null || line.length() < 4) continue;
      String segment = line.substring(0, 3);
      if (pending != null && List.of("OBX", "OBR", "ORC", "PID", "MSH").contains(segment)) {
        results.add(recognize(pending.result(), pending.specimenId(), pending.fields(), recognition));
        noteTarget = -1;
        pending = null;
      }
      if (!"NTE".equals(segment) && !"SPM".equals(segment)) {
        noteTarget = -1;
      }
      if (specimenPosition == Hl7SpecimenPosition.FOLLOWING_OBX && "OBX".equals(segment)) {
        fieldValues.removeSegment("SPM");
      }
      if ("MSH".equals(segment) && line.length() >= 8) {
        delimiters = new Delimiters(line.charAt(3), line.charAt(4), line.charAt(5), line.charAt(7));
      }
      if (line.charAt(3) != delimiters.field()) continue;
      Map<String, String> extracted = extractRecognitionFields(line, segment, delimiters);
      fieldValues.putSegment(segment, extracted);
      if (pending != null && "SPM".equals(segment)) {
        pending.fields().putSegment(segment, extracted);
      }

      if ("OBR".equals(segment)) {
        accession = parseAccessionFromOBR(line, delimiters);
      }
      if (parts != null) {
        if ("PID".equals(segment)) {
          patient = readPatient(fieldValues, parts);
        }
        String descriptor = Hl7ResultParts.read(parts.specimenDescriptor(), fieldValues);
        if (!descriptor.isEmpty()) {
          specimenDescriptor = descriptor;
        }
        if ("NTE".equals(segment)) {
          String note = Hl7ResultParts.read(parts.note(), fieldValues);
          if (!note.isEmpty() && pending != null) {
            pending = pending.withNote(note);
          } else if (!note.isEmpty() && noteTarget >= 0) {
            AnalyzerResult target = results.get(noteTarget);
            results.set(noteTarget, target.withParts(target.parts().withNote(note)));
          }
        }
      }

      SampleFlags.Reported flag = "OBX".equals(segment) && reading != null
        ? reading.sampleFlags().read(fieldValues)
        : null;
      if (flag != null) {
        if (flag.raised()) {
          sampleFlags.add(new SampleFlag(flag.code(), flag.name(), flag.value()));
        }
      } else if ("OBX".equals(segment) && resultRecordSelection.includes(fieldValues)) {
        AnalyzerResult result = parts == null
          ? parseObxSegment(line, delimiters)
          : readObservation(fieldValues, parts, reading, delimiters);
        if (result != null) {
          String specimenId = actualAccession(accession);
          if (specimenPosition == Hl7SpecimenPosition.FOLLOWING_OBX) {
            pending = new PendingObservation(result, specimenId, fieldValues.snapshot());
          } else {
            results.add(recognize(result, specimenId, fieldValues, recognition));
            noteTarget = parts == null ? -1 : results.size() - 1;
          }
        }
      }
    }

    if (pending != null) {
      results.add(recognize(pending.result(), pending.specimenId(), pending.fields(), recognition));
    }
    accession = actualAccession(accession);
    // Recognition already used instrument evidence, never this display-only fallback.
    if (accession == null) accession = "HL7-UNKNOWN";

    return results.isEmpty() ? null : new ParsedResults(accession, results, patient, specimenDescriptor, sampleFlags);
  }

  private record PendingObservation(AnalyzerResult result, String specimenId, SegmentFields fields) {
    PendingObservation withNote(String note) {
      return new PendingObservation(result.withParts(result.parts().withNote(note)), specimenId, fields);
    }
  }

  /**
   * One observation, every part read from the field the profile names. OBX-2 says whether the
   * value is a number (NM, SN) or an answer; an observation with no value says nothing, so it is
   * not a result.
   */
  private static AnalyzerResult readObservation(
    Map<String, String> fields,
    Hl7ResultParts parts,
    ResultReading reading,
    Delimiters delimiters
  ) {
    String testCode = Hl7ResultParts.read(parts.testCode(), fields);
    String value = Hl7ResultParts.read(parts.value(), fields);
    if (testCode.isEmpty() || value.isEmpty()) {
      return null;
    }
    String valueType = Hl7ResultParts.read("OBX.2", fields);
    String canonical = reading.canonicalNumber(value);
    boolean numeric = ("NM".equals(valueType) || "SN".equals(valueType)) && NumericValue.isBoundedDecimal(canonical);
    String subIdentity = Hl7ResultParts.read(parts.subIdentity(), fields);
    String unit = Hl7ResultParts.read(parts.unit(), fields);
    String range = Hl7ResultParts.read(parts.range(), fields);
    List<String> flags = Arrays.stream(split(Hl7ResultParts.read(parts.flag(), fields), delimiters.repetition()))
      .map(String::trim)
      .filter(flag -> !flag.isEmpty())
      .toList();
    RecordParts recordParts = new RecordParts(
      subIdentity,
      numeric ? null : value,
      numeric ? canonical : null,
      null,
      null,
      blankToNull(range),
      flags,
      null,
      null,
      blankToNull(Hl7ResultParts.read(parts.operator(), fields)),
      blankToNull(Hl7ResultParts.read(parts.instrument(), fields)),
      blankToNull(Hl7ResultParts.read(parts.status(), fields)),
      reading.isRunFailure(testCode, subIdentity, value),
      List.of()
    );
    AnalyzerResult result = numeric
      ? AnalyzerResult.numeric(testCode, testCode, value, blankToNull(unit))
      : AnalyzerResult.text(testCode, testCode, value);
    String completed = Hl7ResultParts.read(parts.completed(), fields);
    String time = completed.isEmpty() ? null : ASTMResultParser.astmTime(completed);
    if (time != null) {
      result = result.withTimestamp(time);
    }
    return result.withParts(recordParts);
  }

  /** The patient the instrument reported: the identifier, and the family and given names of the name field. */
  private static InstrumentPatient readPatient(Map<String, String> fields, Hl7ResultParts parts) {
    String identifier = Hl7ResultParts.read(parts.patientId(), fields);
    String name = parts.patientName();
    String family = name == null ? "" : Hl7ResultParts.read(name + ".1", fields);
    String given = name == null ? "" : Hl7ResultParts.read(name + ".2", fields);
    if (identifier.isEmpty() && family.isEmpty() && given.isEmpty()) {
      return null;
    }
    return new InstrumentPatient(blankToNull(identifier), blankToNull(family), blankToNull(given));
  }

  private static String blankToNull(String text) {
    return text == null || text.isEmpty() ? null : text;
  }

  /**
   * Recognition fields keyed by path ({@code OBX.5.1}), held per segment so that replacing a
   * segment's fields, or snapshotting all of them for a pending observation, costs the size of one
   * segment rather than everything seen so far. A segment's own map is never changed once stored.
   */
  private static final class SegmentFields extends AbstractMap<String, String> {

    private final Map<String, Map<String, String>> bySegment;

    SegmentFields() {
      this(new HashMap<>());
    }

    private SegmentFields(Map<String, Map<String, String>> bySegment) {
      this.bySegment = bySegment;
    }

    void putSegment(String segment, Map<String, String> fields) {
      bySegment.put(segment, fields);
    }

    void removeSegment(String segment) {
      bySegment.remove(segment);
    }

    SegmentFields snapshot() {
      return new SegmentFields(new HashMap<>(bySegment));
    }

    @Override
    public String get(Object key) {
      if (!(key instanceof String path)) return null;
      int dot = path.indexOf('.');
      Map<String, String> fields = bySegment.get(dot < 0 ? path : path.substring(0, dot));
      return fields == null ? null : fields.get(path);
    }

    @Override
    public boolean containsKey(Object key) {
      return get(key) != null;
    }

    @Override
    public Set<Entry<String, String>> entrySet() {
      Set<Entry<String, String>> entries = new HashSet<>();
      bySegment.values().forEach(fields -> entries.addAll(fields.entrySet()));
      return entries;
    }
  }

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

  /** The accession the instrument named; a patient identifier is never one. */
  private static String actualAccession(String accession) {
    return accession != null && !accession.isBlank() ? accession : null;
  }

  /**
   * A segment's recognition fields, which replace that segment's prior fields,
   * including absent trailing components. Recognition captures each OBX, so later
   * observations/orders cannot rewrite earlier results. Whole fields retain raw repetitions; component references
   * select the first repetition, as the profile path has no repetition index.
   */
  private static Map<String, String> extractRecognitionFields(String line, String segment, Delimiters delimiters) {
    Map<String, String> values = new HashMap<>();
    String prefix = segment + ".";
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
    return values;
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
    boolean isNumeric = ("NM".equals(valueType) || "SN".equals(valueType)) && NumericValue.isBoundedDecimal(value);

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

  /**
   * @param patient the patient the instrument reported, when its profile says where to read one
   * @param specimenDescriptor the instrument's own description of the specimen, as sent
   * @param sampleFlags the warnings the instrument raised about the sample, in the order sent
   */
  public record ParsedResults(
    String accessionNumber,
    List<AnalyzerResult> results,
    InstrumentPatient patient,
    String specimenDescriptor,
    List<SampleFlag> sampleFlags
  ) {
    public ParsedResults {
      sampleFlags = sampleFlags == null ? List.of() : List.copyOf(sampleFlags);
    }

    public ParsedResults(String accessionNumber, List<AnalyzerResult> results) {
      this(accessionNumber, results, null, null, List.of());
    }

    public ParsedResults(
      String accessionNumber,
      List<AnalyzerResult> results,
      InstrumentPatient patient,
      String specimenDescriptor
    ) {
      this(accessionNumber, results, patient, specimenDescriptor, List.of());
    }

    /** These results under the profile's own codes, where the connection sets other codes for its instrument. */
    public ParsedResults underProfileCodes(org.itech.ahb.profile.ResultReading reading) {
      if (reading == null || reading.profileCodeByInstrumentCode().isEmpty()) {
        return this;
      }
      return new ParsedResults(
        accessionNumber,
        results.stream().map(result -> result.withTestCode(reading.profileCode(result.testCode()))).toList(),
        patient,
        specimenDescriptor,
        sampleFlags
      );
    }
  }
}
