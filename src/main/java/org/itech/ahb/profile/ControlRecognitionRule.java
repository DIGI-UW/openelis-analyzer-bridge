package org.itech.ahb.profile;

import java.util.Set;

/**
 * One named matcher from a published analyzer profile revision.
 *
 * @param key stable key within the profile revision
 * @param ruleType FIELD_EQUALS, FIELD_CONTAINS, SPECIMEN_ID_PREFIX, or
 *     SPECIMEN_ID_PATTERN
 * @param targetField protocol field to inspect for field-based matchers
 * @param operand comparison value, prefix, or regular expression
 * @param controlLevel optional analyzer-reported control level
 * @param controlType optional analyzer-reported control type
 */
public record ControlRecognitionRule(
  String key,
  String ruleType,
  String targetField,
  String operand,
  String controlLevel,
  String controlType
) {

  private static final Set<String> SUPPORTED_RULE_TYPES = Set.of(
    "FIELD_EQUALS",
    "FIELD_CONTAINS",
    "SPECIMEN_ID_PREFIX",
    "SPECIMEN_ID_PATTERN"
  );

  public ControlRecognitionRule {
    requireNonBlank(key, "key");
    requireNonBlank(ruleType, "ruleType");
    requireNonBlank(operand, "operand");
    if (!SUPPORTED_RULE_TYPES.contains(ruleType)) {
      throw new IllegalArgumentException("Unsupported control recognition rule type " + ruleType);
    }
    if (("FIELD_EQUALS".equals(ruleType) || "FIELD_CONTAINS".equals(ruleType)) &&
      (targetField == null || targetField.isBlank())) {
      throw new IllegalArgumentException("targetField is required for " + ruleType);
    }
  }

  private static void requireNonBlank(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " is required");
    }
  }
}
