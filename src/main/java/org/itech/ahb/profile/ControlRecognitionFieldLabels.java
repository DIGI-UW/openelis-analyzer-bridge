package org.itech.ahb.profile;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Human labels for protocol fields used by control-result recognition. */
final class ControlRecognitionFieldLabels {

  private static final Pattern ASTM_FIELD = Pattern.compile("^([A-Z])([0-9]?)\\.([1-9][0-9]*)(?:\\..*)?$");
  private static final Pattern HL7_FIELD = Pattern.compile("^([A-Z0-9]{3})\\.([1-9][0-9]*)(?:\\..*)?$");

  private static final Map<String, String> ASTM_RECORD_LABELS = Map.of(
    "H",
    "Header",
    "P",
    "Patient",
    "O",
    "Order",
    "R",
    "Result",
    "C",
    "Comment",
    "Q",
    "Query",
    "L",
    "Terminator"
  );
  private static final Map<String, String> HL7_SEGMENT_LABELS = Map.ofEntries(
    Map.entry("MSH", "Message header"),
    Map.entry("PID", "Patient"),
    Map.entry("PV1", "Patient visit"),
    Map.entry("ORC", "Common order"),
    Map.entry("OBR", "Observation request"),
    Map.entry("OBX", "Observation result"),
    Map.entry("SPM", "Specimen"),
    Map.entry("SAC", "Specimen container"),
    Map.entry("NTE", "Notes")
  );
  private static final Map<String, String> FILE_FIELD_LABELS = Map.ofEntries(
    Map.entry("SAMPLE_ID", "Specimen ID"),
    Map.entry("TEST_CODE", "Test code"),
    Map.entry("RESULT", "Result"),
    Map.entry("INTERPRETATION", "Interpretation"),
    Map.entry("QC_TASK", "Control task"),
    Map.entry("UNITS", "Units"),
    Map.entry("TEST_DATE", "Test date"),
    Map.entry("CT_VALUE", "Cycle threshold")
  );

  private ControlRecognitionFieldLabels() {}

  static String label(String targetField) {
    if (FILE_FIELD_LABELS.containsKey(targetField)) {
      return FILE_FIELD_LABELS.get(targetField);
    }
    if (ASTM_FIELD.matcher(targetField == null ? "" : targetField).matches()) {
      return label("ASTM", targetField);
    }
    if (HL7_FIELD.matcher(targetField == null ? "" : targetField).matches()) {
      return label("HL7", targetField);
    }
    return "Configured analyzer field";
  }

  static String label(String protocol, String targetField) {
    if ("FILE".equals(protocol)) {
      return FILE_FIELD_LABELS.getOrDefault(targetField, "Configured file field");
    }
    if ("ASTM".equals(protocol)) {
      Matcher matcher = ASTM_FIELD.matcher(targetField == null ? "" : targetField);
      if (matcher.matches()) {
        String record = ASTM_RECORD_LABELS.getOrDefault(matcher.group(1), "Analyzer record");
        String sequence = matcher.group(2).isEmpty() ? "" : " " + matcher.group(2);
        return record + sequence + " field " + matcher.group(3);
      }
      return "Configured ASTM field";
    }
    if ("HL7".equals(protocol)) {
      Matcher matcher = HL7_FIELD.matcher(targetField == null ? "" : targetField);
      if (matcher.matches()) {
        String segment = HL7_SEGMENT_LABELS.getOrDefault(matcher.group(1), "Analyzer message");
        return segment + " field " + matcher.group(2);
      }
      return "Configured HL7 field";
    }
    return label(targetField);
  }

  static boolean isFileField(String targetField) {
    return FILE_FIELD_LABELS.containsKey(targetField);
  }
}
