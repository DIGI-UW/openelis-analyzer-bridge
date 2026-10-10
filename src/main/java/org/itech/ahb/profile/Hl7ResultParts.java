package org.itech.ahb.profile;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Where each part of a result sits in an instrument's HL7 message, as the pinned profile's
 * {@code resultParts} declare them. A part is a field path, {@code SEG.field},
 * {@code SEG.field.component} or {@code SEG.field.component.subcomponent}, the same paths control
 * recognition reads: {@code OBX.5} is the whole observation value, {@code PID.3.1} the first
 * component of the patient identifier. Parts outside the OBX are read from the segment that last
 * preceded the observation; a note is read from the NTE that follows it.
 */
public record Hl7ResultParts(
  String testCode,
  String subIdentity,
  String value,
  String unit,
  String range,
  String flag,
  String status,
  String operator,
  String completed,
  String instrument,
  String note,
  String patientId,
  String patientName,
  String specimenDescriptor
) {
  private static final Pattern PATH = Pattern.compile("^(OBX|OBR|PID|NTE|SPM)\\.[1-9][0-9]*(\\.[1-9][0-9]*){0,2}$");

  public Hl7ResultParts {
    Objects.requireNonNull(testCode, "A profile that declares result parts says where the test code sits");
    Objects.requireNonNull(value, "A profile that declares result parts says where the value sits");
  }

  /** The profile's parts, or null when it declares none and is read the way it always was. */
  public static Hl7ResultParts fromProfile(JsonNode configDefaults) {
    JsonNode parts = configDefaults.path("extractionOverrides").path("resultParts");
    if (!parts.isObject()) {
      return null;
    }
    return new Hl7ResultParts(
      path(parts, "testCode"),
      path(parts, "subIdentity"),
      path(parts, "value"),
      path(parts, "unit"),
      path(parts, "range"),
      path(parts, "flag"),
      path(parts, "status"),
      path(parts, "operator"),
      path(parts, "completed"),
      path(parts, "instrument"),
      path(parts, "note"),
      path(parts, "patientId"),
      path(parts, "patientName"),
      path(parts, "specimenDescriptor")
    );
  }

  /** The part's text in the message's fields, trimmed; empty when the profile does not declare it. */
  public static String read(String part, Map<String, String> fields) {
    if (part == null) {
      return "";
    }
    String text = fields.get(part);
    return text == null ? "" : text.trim();
  }

  private static String path(JsonNode parts, String name) {
    String reference = parts.path(name).asText(null);
    if (reference == null || reference.isBlank()) {
      return null;
    }
    if (!PATH.matcher(reference).matches()) {
      throw new IllegalArgumentException("Not an HL7 field path: " + reference);
    }
    return reference;
  }
}
