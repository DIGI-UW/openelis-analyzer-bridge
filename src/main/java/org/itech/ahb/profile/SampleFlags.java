package org.itech.ahb.profile;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The codes an instrument sends as yes or no warnings about a sample, such as a hematology
 * analyzer's alarms, as the pinned profile's {@code sampleFlags} declare them: where a record's
 * code, name and value sit, the codes, and the value that means raised. A record carrying one of
 * these codes is never a result: a raised one is a flag on the sample, and one not raised says
 * nothing.
 */
public record SampleFlags(
  String codeField,
  String nameField,
  String valueField,
  String raisedValue,
  Set<String> codes
) {
  private static final Pattern HL7_FIELD = Pattern.compile("^OBX\\.[1-9][0-9]*(\\.[1-9][0-9]*){0,2}$");

  public SampleFlags {
    codes = codes == null ? Set.of() : Set.copyOf(codes);
  }

  public static SampleFlags none() {
    return new SampleFlags(null, null, null, null, Set.of());
  }

  /**
   * The flags an ASTM or HL7 profile declares; a profile that declares none, or of another
   * protocol, has no record that is a flag.
   */
  public static SampleFlags fromProfile(JsonNode configDefaults, String protocol) {
    JsonNode flags = configDefaults.path("extractionOverrides").path("sampleFlags");
    if (!flags.isObject() || !("ASTM".equals(protocol) || "HL7".equals(protocol))) {
      return none();
    }
    Set<String> codes = new LinkedHashSet<>();
    flags.path("codes").forEach(code -> codes.add(code.asText()));
    SampleFlags declared = new SampleFlags(
      text(flags, "codeField"),
      text(flags, "nameField"),
      text(flags, "valueField"),
      text(flags, "raisedValue"),
      codes
    );
    if (
      declared.codeField() == null || declared.valueField() == null || declared.raisedValue() == null || codes.isEmpty()
    ) {
      throw new IllegalArgumentException(
        "sampleFlags names where the code and value sit, the value that means raised, and at least one code"
      );
    }
    for (String field : new String[] { declared.codeField(), declared.nameField(), declared.valueField() }) {
      if (field != null && !locates(field, protocol)) {
        throw new IllegalArgumentException("sampleFlags field " + field + " is not a " + protocol + " result field");
      }
    }
    return declared;
  }

  /** The flag an HL7 observation carries, or null when its code is not one the profile names. */
  public Reported read(Map<String, String> observationFields) {
    if (codes.isEmpty()) {
      return null;
    }
    return reported(
      Hl7ResultParts.read(codeField, observationFields),
      Hl7ResultParts.read(nameField, observationFields),
      Hl7ResultParts.read(valueField, observationFields)
    );
  }

  /** The flag an ASTM result record carries, or null when its code is not one the profile names. */
  public Reported read(String[] recordFields) {
    if (codes.isEmpty()) {
      return null;
    }
    return reported(astm(codeField, recordFields), astm(nameField, recordFields), astm(valueField, recordFields));
  }

  /** A record that carries a flag: its code, the instrument's name for it, its value and whether that raises it. */
  public record Reported(String code, String name, String value, boolean raised) {}

  private Reported reported(String code, String name, String value) {
    if (!codes.contains(code)) {
      return null;
    }
    return new Reported(code, name.isEmpty() ? code : name, value, raisedValue.equals(value));
  }

  private static String astm(String field, String[] recordFields) {
    return field == null ? "" : AstmResultParts.Field.parse(field).read(recordFields);
  }

  private static boolean locates(String field, String protocol) {
    if ("HL7".equals(protocol)) {
      return HL7_FIELD.matcher(field).matches();
    }
    try {
      return AstmResultParts.Field.parse(field).recordType() == 'R';
    } catch (IllegalArgumentException notAField) {
      return false;
    }
  }

  private static String text(JsonNode node, String name) {
    String value = node.path(name).asText(null);
    return value == null || value.isBlank() ? null : value;
  }
}
