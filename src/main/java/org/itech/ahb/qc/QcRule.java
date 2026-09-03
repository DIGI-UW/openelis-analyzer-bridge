package org.itech.ahb.qc;

/**
 * A profile-owned control-result recognition rule.
 * Rules use OR semantics: if any rule matches, the sample is a control.
 *
 * @param ruleType       FIELD_EQUALS, SPECIMEN_ID_PREFIX, SPECIMEN_ID_PATTERN, FIELD_CONTAINS
 * @param targetField    e.g., "O.12", "QC_TASK" (null for SPECIMEN_ID_* rules)
 * @param operand        the comparison value, prefix, or regex pattern
 */
public record QcRule(
        String ruleType,
        String targetField,
        String operand) {
}
