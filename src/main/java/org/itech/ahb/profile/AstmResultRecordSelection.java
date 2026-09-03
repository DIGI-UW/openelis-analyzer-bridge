package org.itech.ahb.profile;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Profile-owned rule for deciding which ASTM R records carry results. */
public record AstmResultRecordSelection(Mode mode, String targetField) {

  private static final Pattern FIELD_REFERENCE = Pattern.compile("^R\\.([1-9][0-9]*)\\.([1-9][0-9]*)$");

  public enum Mode {
    ALL,
    FIELD_NON_BLANK
  }

  public AstmResultRecordSelection {
    Objects.requireNonNull(mode, "mode");
    if (mode == Mode.ALL && targetField != null) {
      throw new IllegalArgumentException("targetField is not allowed for ALL result-record selection");
    }
    if (mode == Mode.FIELD_NON_BLANK
        && (targetField == null || !FIELD_REFERENCE.matcher(targetField).matches())) {
      throw new IllegalArgumentException(
        "FIELD_NON_BLANK result-record selection requires an R.field.component target"
      );
    }
  }

  public static AstmResultRecordSelection all() {
    return new AstmResultRecordSelection(Mode.ALL, null);
  }

  public static AstmResultRecordSelection fieldNonBlank(String targetField) {
    return new AstmResultRecordSelection(Mode.FIELD_NON_BLANK, targetField);
  }

  public static AstmResultRecordSelection fromProfile(JsonNode configDefaults) {
    JsonNode selection = configDefaults
      .path("extractionOverrides")
      .path("resultRecordSelection");
    String configuredMode = selection.path("mode").asText(null);
    if (configuredMode == null) {
      throw new IllegalArgumentException("ASTM resultRecordSelection mode is required");
    }
    try {
      Mode mode = Mode.valueOf(configuredMode);
      String target = selection.path("targetField").asText(null);
      return new AstmResultRecordSelection(mode, target);
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException(
        "Invalid ASTM resultRecordSelection: " + exception.getMessage(),
        exception
      );
    }
  }

  public boolean includes(String resultRecord) {
    if (mode == Mode.ALL) {
      return true;
    }
    if (resultRecord == null || resultRecord.isBlank()) {
      return false;
    }

    Matcher reference = FIELD_REFERENCE.matcher(targetField);
    if (!reference.matches()) {
      return false;
    }
    int fieldIndex = Integer.parseInt(reference.group(1)) - 1;
    int componentIndex = Integer.parseInt(reference.group(2)) - 1;
    String[] fields = resultRecord.split(Pattern.quote("|"), -1);
    if (fieldIndex >= fields.length) {
      return false;
    }
    String[] components = fields[fieldIndex].split(Pattern.quote("^"), -1);
    return componentIndex < components.length && !components[componentIndex].isBlank();
  }
}
