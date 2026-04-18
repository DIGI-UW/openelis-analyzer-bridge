package org.itech.ahb.qc;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import lombok.extern.slf4j.Slf4j;

/**
 * Evaluates QC identification rules against parsed message fields.
 *
 * <p>Rules use OR semantics: a sample is flagged as QC if ANY rule matches.
 * Thread-safe — compiled regex patterns are cached in a static ConcurrentHashMap.
 */
@Slf4j
public final class QcRuleEvaluator {

    private static final ConcurrentHashMap<String, Pattern> PATTERN_CACHE = new ConcurrentHashMap<>();

    private QcRuleEvaluator() {
    }

    /**
     * Evaluate QC rules against available field values.
     *
     * @param rules       the QC rules to evaluate
     * @param specimenId  the specimen/accession ID
     * @param fieldValues map of field reference to field value (e.g., {"O.12": "Q", "QC_TASK": "CONTROL"})
     * @return true if ANY rule matches (OR semantics)
     */
    public static boolean isQcSample(List<QcRule> rules, String specimenId,
            Map<String, String> fieldValues) {
        if (rules == null || rules.isEmpty()) {
            return false;
        }

        for (QcRule rule : rules) {
            if (evaluateRule(rule, specimenId, fieldValues)) {
                log.debug("QC rule matched: type={}, field={}, operand={}",
                        rule.ruleType(), rule.targetField(), rule.operand());
                return true;
            }
        }
        return false;
    }

    private static boolean evaluateRule(QcRule rule, String specimenId,
            Map<String, String> fieldValues) {
        return switch (rule.ruleType()) {
            case "FIELD_EQUALS" -> {
                String value = fieldValues != null
                        ? fieldValues.get(rule.targetField()) : null;
                yield value != null
                        && value.trim().equalsIgnoreCase(rule.operand().trim());
            }
            case "FIELD_CONTAINS" -> {
                String value = fieldValues != null
                        ? fieldValues.get(rule.targetField()) : null;
                // Locale.ROOT so case-folding is deterministic across JVM locales.
                // In Turkish locale, "i".toUpperCase() yields "İ" (U+0130), not "I",
                // which breaks case-insensitive matching when either side contains 'i'.
                yield value != null
                        && value.toUpperCase(Locale.ROOT)
                                .contains(rule.operand().toUpperCase(Locale.ROOT));
            }
            case "SPECIMEN_ID_PREFIX" -> {
                // Same Locale.ROOT fix as FIELD_CONTAINS above.
                yield specimenId != null
                        && specimenId.toUpperCase(Locale.ROOT)
                                .startsWith(rule.operand().toUpperCase(Locale.ROOT));
            }
            case "SPECIMEN_ID_PATTERN" -> {
                if (specimenId == null) yield false;
                try {
                    Pattern pattern = PATTERN_CACHE.computeIfAbsent(rule.operand(),
                            k -> Pattern.compile(k, Pattern.CASE_INSENSITIVE));
                    yield pattern.matcher(specimenId).matches();
                } catch (PatternSyntaxException e) {
                    log.warn("Invalid QC regex '{}': {}", rule.operand(), e.getMessage());
                    yield false;
                }
            }
            default -> {
                log.warn("Unknown QC rule type: {}", rule.ruleType());
                yield false;
            }
        };
    }
}
