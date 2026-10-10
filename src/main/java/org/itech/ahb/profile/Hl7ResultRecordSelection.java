package org.itech.ahb.profile;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Profile-owned rule for deciding which HL7 OBX segments carry results. An instrument that also
 * sends its settings, images or alarms as OBX names them by code, and those segments are not
 * results; every other OBX is, including a code the profile does not declare.
 */
public record Hl7ResultRecordSelection(Mode mode, String targetField, Set<String> values) {
  private static final Pattern FIELD_PATH = Pattern.compile("^OBX\\.[1-9][0-9]*(\\.[1-9][0-9]*){0,2}$");

  public enum Mode {
    ALL,
    EXCLUDE
  }

  public Hl7ResultRecordSelection {
    Objects.requireNonNull(mode, "mode");
    values = values == null ? Set.of() : Set.copyOf(values);
    if (mode == Mode.ALL && (targetField != null || !values.isEmpty())) {
      throw new IllegalArgumentException("targetField and values are not allowed for ALL result-record selection");
    }
    if (
      mode == Mode.EXCLUDE && (targetField == null || !FIELD_PATH.matcher(targetField).matches() || values.isEmpty())
    ) {
      throw new IllegalArgumentException(
        "EXCLUDE result-record selection requires an OBX.field target and the values it leaves out"
      );
    }
  }

  public static Hl7ResultRecordSelection all() {
    return new Hl7ResultRecordSelection(Mode.ALL, null, Set.of());
  }

  /** The profile's selection; a profile that declares none reads every OBX as a result. */
  public static Hl7ResultRecordSelection fromProfile(JsonNode configDefaults) {
    JsonNode selection = configDefaults.path("extractionOverrides").path("resultRecordSelection");
    if (selection.isMissingNode()) {
      return all();
    }
    try {
      Mode mode = Mode.valueOf(selection.path("mode").asText(""));
      Set<String> values = new LinkedHashSet<>();
      selection.path("values").forEach(value -> values.add(value.asText()));
      return new Hl7ResultRecordSelection(mode, selection.path("targetField").asText(null), values);
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("Invalid HL7 resultRecordSelection: " + exception.getMessage(), exception);
    }
  }

  /** Whether the observation whose fields these are carries a result. */
  public boolean includes(Map<String, String> observationFields) {
    if (mode == Mode.ALL) {
      return true;
    }
    String value = observationFields.get(targetField);
    return value == null || !values.contains(value.trim());
  }
}
