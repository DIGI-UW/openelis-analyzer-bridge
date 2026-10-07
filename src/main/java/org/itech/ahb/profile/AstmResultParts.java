package org.itech.ahb.profile;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where each part of a result sits in an instrument's ASTM records, as the pinned profile's
 * {@code resultParts} declare them. A part is a reference to a field, or one component of a field,
 * of one record type: {@code R.3.4} is component 4 of field 3 of the R record.
 */
public record AstmResultParts(
  Field testCode,
  Field assayName,
  Field assayVersion,
  Field analyte,
  Field complement,
  Field call,
  Field number,
  Field unit,
  Field range,
  Field flag,
  Field status,
  Field operator,
  Field started,
  Field completed,
  Field instrument,
  Field note,
  Field patientId,
  Field patientName,
  Field specimenDescriptor
) {
  private static final Pattern REFERENCE = Pattern.compile("^([A-Z])\\.([1-9][0-9]*)(?:\\.([1-9][0-9]*))?$");

  public AstmResultParts {
    Objects.requireNonNull(testCode, "A profile that declares result parts says where the test code sits");
  }

  /** The profile's parts, or null when it declares none and is read the way it always was. */
  public static AstmResultParts fromProfile(JsonNode configDefaults) {
    JsonNode parts = configDefaults.path("extractionOverrides").path("resultParts");
    if (!parts.isObject()) {
      return null;
    }
    return new AstmResultParts(
      Field.parse(parts.path("testCode").asText(null)),
      Field.parse(parts.path("assayName").asText(null)),
      Field.parse(parts.path("assayVersion").asText(null)),
      Field.parse(parts.path("analyte").asText(null)),
      Field.parse(parts.path("complement").asText(null)),
      Field.parse(parts.path("call").asText(null)),
      Field.parse(parts.path("number").asText(null)),
      Field.parse(parts.path("unit").asText(null)),
      Field.parse(parts.path("range").asText(null)),
      Field.parse(parts.path("flag").asText(null)),
      Field.parse(parts.path("status").asText(null)),
      Field.parse(parts.path("operator").asText(null)),
      Field.parse(parts.path("started").asText(null)),
      Field.parse(parts.path("completed").asText(null)),
      Field.parse(parts.path("instrument").asText(null)),
      Field.parse(parts.path("note").asText(null)),
      Field.parse(parts.path("patientId").asText(null)),
      Field.parse(parts.path("patientName").asText(null)),
      Field.parse(parts.path("specimenDescriptor").asText(null))
    );
  }

  /** One field, or one component of a field, of an ASTM record; absent parts read as empty. */
  public record Field(char recordType, int field, int component) {
    static Field parse(String reference) {
      if (reference == null || reference.isBlank()) {
        return null;
      }
      Matcher matcher = REFERENCE.matcher(reference);
      if (!matcher.matches()) {
        throw new IllegalArgumentException("Not an ASTM field reference: " + reference);
      }
      return new Field(
        matcher.group(1).charAt(0),
        Integer.parseInt(matcher.group(2)),
        matcher.group(3) == null ? 0 : Integer.parseInt(matcher.group(3))
      );
    }

    /** The text of this part in a record split on its field delimiter, trimmed; empty when absent. */
    public String read(String[] fields) {
      if (field > fields.length) {
        return "";
      }
      String text = fields[field - 1];
      if (component == 0) {
        return text.trim();
      }
      String[] components = text.split(Pattern.quote("^"), -1);
      return component <= components.length ? components[component - 1].trim() : "";
    }
  }

  /** The part of a record, or empty when the profile does not declare it. */
  public static String read(Field part, String[] fields) {
    return part == null ? "" : part.read(fields);
  }
}
