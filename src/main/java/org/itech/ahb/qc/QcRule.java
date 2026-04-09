package org.itech.ahb.qc;

/**
 * A QC sample identification rule, pulled from OE.
 * Rules use OR semantics: if ANY active rule matches, the sample is QC.
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
