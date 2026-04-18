package org.itech.ahb.qc;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("QcRuleEvaluator")
class QcRuleEvaluatorTest {

    @Nested
    @DisplayName("FIELD_EQUALS")
    class FieldEquals {

        @Test
        @DisplayName("Case-insensitive exact match returns true")
        void exactMatch_CaseInsensitive() {
            List<QcRule> rules = List.of(new QcRule("FIELD_EQUALS", "O.12", "Q"));
            Map<String, String> fields = Map.of("O.12", "q");
            assertTrue(QcRuleEvaluator.isQcSample(rules, "SAMPLE001", fields));
        }

        @Test
        @DisplayName("No match returns false")
        void noMatch() {
            List<QcRule> rules = List.of(new QcRule("FIELD_EQUALS", "O.12", "Q"));
            Map<String, String> fields = Map.of("O.12", "P");
            assertFalse(QcRuleEvaluator.isQcSample(rules, "SAMPLE001", fields));
        }

        @Test
        @DisplayName("Target field missing from map returns false")
        void targetFieldMissing() {
            List<QcRule> rules = List.of(new QcRule("FIELD_EQUALS", "O.12", "Q"));
            Map<String, String> fields = Map.of("O.11", "Q");
            assertFalse(QcRuleEvaluator.isQcSample(rules, "SAMPLE001", fields));
        }

        @Test
        @DisplayName("Null field values returns false")
        void nullFieldValues() {
            List<QcRule> rules = List.of(new QcRule("FIELD_EQUALS", "O.12", "Q"));
            assertFalse(QcRuleEvaluator.isQcSample(rules, "SAMPLE001", null));
        }

        @Test
        @DisplayName("Whitespace trimming matches after trim")
        void whitespaceTrimming() {
            List<QcRule> rules = List.of(new QcRule("FIELD_EQUALS", "O.12", " Q "));
            Map<String, String> fields = Map.of("O.12", "  Q  ");
            assertTrue(QcRuleEvaluator.isQcSample(rules, "SAMPLE001", fields));
        }
    }

    @Nested
    @DisplayName("FIELD_CONTAINS")
    class FieldContains {

        @Test
        @DisplayName("Case-insensitive substring match returns true")
        void substringMatch_CaseInsensitive() {
            List<QcRule> rules = List.of(new QcRule("FIELD_CONTAINS", "QC_TASK", "control"));
            Map<String, String> fields = Map.of("QC_TASK", "Internal CONTROL Sample");
            assertTrue(QcRuleEvaluator.isQcSample(rules, "SAMPLE001", fields));
        }

        @Test
        @DisplayName("No substring match returns false")
        void noMatch() {
            List<QcRule> rules = List.of(new QcRule("FIELD_CONTAINS", "QC_TASK", "CONTROL"));
            Map<String, String> fields = Map.of("QC_TASK", "PATIENT");
            assertFalse(QcRuleEvaluator.isQcSample(rules, "SAMPLE001", fields));
        }

        @Test
        @DisplayName("Null field values returns false")
        void nullFieldValues() {
            List<QcRule> rules = List.of(new QcRule("FIELD_CONTAINS", "QC_TASK", "CONTROL"));
            assertFalse(QcRuleEvaluator.isQcSample(rules, "SAMPLE001", null));
        }

        @Test
        @DisplayName("Target field missing from map returns false")
        void targetFieldMissing() {
            List<QcRule> rules = List.of(new QcRule("FIELD_CONTAINS", "QC_TASK", "CONTROL"));
            Map<String, String> fields = Map.of("OTHER_FIELD", "CONTROL");
            assertFalse(QcRuleEvaluator.isQcSample(rules, "SAMPLE001", fields));
        }
    }

    @Nested
    @DisplayName("SPECIMEN_ID_PREFIX")
    class SpecimenIdPrefix {

        @Test
        @DisplayName("Case-insensitive prefix match returns true")
        void matchingPrefix_CaseInsensitive() {
            List<QcRule> rules = List.of(new QcRule("SPECIMEN_ID_PREFIX", null, "qc-"));
            assertTrue(QcRuleEvaluator.isQcSample(rules, "QC-2026-001", Map.of()));
        }

        @Test
        @DisplayName("No prefix match returns false")
        void noMatch() {
            List<QcRule> rules = List.of(new QcRule("SPECIMEN_ID_PREFIX", null, "QC-"));
            assertFalse(QcRuleEvaluator.isQcSample(rules, "SAMPLE-001", Map.of()));
        }

        @Test
        @DisplayName("Null specimen ID returns false")
        void nullSpecimenId() {
            List<QcRule> rules = List.of(new QcRule("SPECIMEN_ID_PREFIX", null, "QC-"));
            assertFalse(QcRuleEvaluator.isQcSample(rules, null, Map.of()));
        }

        @Test
        @DisplayName("Empty specimen ID returns false")
        void emptySpecimenId() {
            List<QcRule> rules = List.of(new QcRule("SPECIMEN_ID_PREFIX", null, "QC-"));
            assertFalse(QcRuleEvaluator.isQcSample(rules, "", Map.of()));
        }
    }

    @Nested
    @DisplayName("SPECIMEN_ID_PATTERN")
    class SpecimenIdPattern {

        @Test
        @DisplayName("Matching regex returns true")
        void matchingRegex() {
            List<QcRule> rules = List.of(new QcRule("SPECIMEN_ID_PATTERN", null, "^CTRL-\\d{4}$"));
            assertTrue(QcRuleEvaluator.isQcSample(rules, "CTRL-1234", Map.of()));
        }

        @Test
        @DisplayName("Non-matching regex returns false")
        void nonMatchingRegex() {
            List<QcRule> rules = List.of(new QcRule("SPECIMEN_ID_PATTERN", null, "^CTRL-\\d{4}$"));
            assertFalse(QcRuleEvaluator.isQcSample(rules, "CTRL-ABC", Map.of()));
        }

        @Test
        @DisplayName("Case-insensitive flag works")
        void caseInsensitiveFlag() {
            List<QcRule> rules = List.of(new QcRule("SPECIMEN_ID_PATTERN", null, "^ctrl-\\d{4}$"));
            assertTrue(QcRuleEvaluator.isQcSample(rules, "CTRL-1234", Map.of()));
        }

        @Test
        @DisplayName("Null specimen ID returns false")
        void nullSpecimenId() {
            List<QcRule> rules = List.of(new QcRule("SPECIMEN_ID_PATTERN", null, "^QC.*"));
            assertFalse(QcRuleEvaluator.isQcSample(rules, null, Map.of()));
        }

        @Test
        @DisplayName("Invalid regex returns false without throwing")
        void invalidRegex_ReturnsFalse() {
            List<QcRule> rules = List.of(new QcRule("SPECIMEN_ID_PATTERN", null, "[invalid("));
            assertFalse(QcRuleEvaluator.isQcSample(rules, "test", Map.of()));
        }

        @Test
        @DisplayName("Cached pattern works on second invocation")
        void patternCached() {
            List<QcRule> rules = List.of(new QcRule("SPECIMEN_ID_PATTERN", null, "^QC-\\d+$"));
            assertTrue(QcRuleEvaluator.isQcSample(rules, "QC-001", Map.of()));
            assertTrue(QcRuleEvaluator.isQcSample(rules, "QC-999", Map.of()));
        }
    }

    @Nested
    @DisplayName("OR semantics")
    class OrSemantics {

        @Test
        @DisplayName("First rule matches — short-circuits to true")
        void firstRuleMatches() {
            List<QcRule> rules = List.of(
                    new QcRule("FIELD_EQUALS", "O.12", "Q"),
                    new QcRule("SPECIMEN_ID_PREFIX", null, "SAMPLE-"));
            Map<String, String> fields = Map.of("O.12", "Q");
            assertTrue(QcRuleEvaluator.isQcSample(rules, "REGULAR-001", fields));
        }

        @Test
        @DisplayName("Second rule matches — first does not — returns true")
        void secondRuleMatches() {
            List<QcRule> rules = List.of(
                    new QcRule("FIELD_EQUALS", "O.12", "X"),
                    new QcRule("SPECIMEN_ID_PREFIX", null, "QC-"));
            Map<String, String> fields = Map.of("O.12", "P");
            // First rule: O.12="P" != "X" → no match
            // Second rule: specimenId "QC-0001" starts with "QC-" → match
            assertTrue(QcRuleEvaluator.isQcSample(rules, "QC-0001", fields));
        }

        @Test
        @DisplayName("No rules match — returns false")
        void noRulesMatch() {
            List<QcRule> rules = List.of(
                    new QcRule("FIELD_EQUALS", "O.12", "Q"),
                    new QcRule("SPECIMEN_ID_PREFIX", null, "QC-"));
            Map<String, String> fields = Map.of("O.12", "P");
            assertFalse(QcRuleEvaluator.isQcSample(rules, "SAMPLE-001", fields));
        }
    }

    @Nested
    @DisplayName("Locale independence — regression guard against JVM default locale")
    class LocaleIndependence {
        // Turkish is the canonical case-folding outlier in Java:
        //   "i".toUpperCase(Locale.forLanguageTag("tr")) → "İ" (U+0130), not "I"
        //   "I".toLowerCase(Locale.forLanguageTag("tr")) → "ı" (U+0131), not "i"
        // So if FIELD_CONTAINS / SPECIMEN_ID_PREFIX use the default-locale toUpperCase()
        // and the JVM happens to boot in Turkish locale, any rule involving an
        // 'i'/'I' character silently misfires. Locking the test locale to tr-TR
        // around each assertion proves the production code case-folds through
        // Locale.ROOT (or equivalent), not the ambient JVM locale.
        //
        // This is a regression guard, not a "we serve Turkish labs" test. If the
        // assertion passes in tr-TR, it passes in every other locale too.

        private static final Locale TURKISH = Locale.forLanguageTag("tr-TR");
        private Locale originalLocale;

        @BeforeEach
        void forceTurkishLocale() {
            originalLocale = Locale.getDefault();
            Locale.setDefault(TURKISH);
        }

        @AfterEach
        void restoreLocale() {
            Locale.setDefault(originalLocale);
        }

        @Test
        @DisplayName("FIELD_CONTAINS matches 'Positive'/'POSITIVE' in Turkish locale")
        void fieldContains_TurkishLocale_stillMatchesOnI() {
            // 'POSITIVE'.toUpperCase(tr-TR) = 'POSİTİVE' (note the İ); 'Positive'.toUpperCase(tr-TR) = 'POSİTİVE'.
            // With Locale.ROOT these are both 'POSITIVE'. Either way the operand + value
            // must fold consistently so contains() returns true.
            List<QcRule> rules = List.of(new QcRule("FIELD_CONTAINS", "QC_TASK", "Positive"));
            Map<String, String> fields = Map.of("QC_TASK", "Control POSITIVE sample");
            assertTrue(QcRuleEvaluator.isQcSample(rules, "SAMPLE001", fields),
                    "FIELD_CONTAINS must be locale-insensitive; failed under tr-TR default locale");
        }

        @Test
        @DisplayName("FIELD_CONTAINS mismatched case across 'i' variants still matches")
        void fieldContains_TurkishLocale_mixedCaseI() {
            // Operand has lowercase 'i', value has uppercase 'I'. Under tr-TR default
            // folding they'd diverge (İ vs I). Under Locale.ROOT they both normalize.
            List<QcRule> rules = List.of(new QcRule("FIELD_CONTAINS", "QC_TASK", "calibration"));
            Map<String, String> fields = Map.of("QC_TASK", "CALIBRATION run 12");
            assertTrue(QcRuleEvaluator.isQcSample(rules, "SAMPLE001", fields),
                    "FIELD_CONTAINS with i↔I case mismatch must still match in Turkish locale");
        }

        @Test
        @DisplayName("SPECIMEN_ID_PREFIX matches 'QI-'/'qi-' in Turkish locale")
        void specimenIdPrefix_TurkishLocale_matchesOnI() {
            // Prefix contains 'i'; specimen contains 'I'. Default-locale folding in
            // tr-TR would break this; Locale.ROOT does not.
            List<QcRule> rules = List.of(new QcRule("SPECIMEN_ID_PREFIX", null, "qi-"));
            assertTrue(QcRuleEvaluator.isQcSample(rules, "QI-2026-001", Map.of()),
                    "SPECIMEN_ID_PREFIX must be locale-insensitive; failed under tr-TR default locale");
        }

        @Test
        @DisplayName("SPECIMEN_ID_PREFIX does NOT match non-prefix even in Turkish locale")
        void specimenIdPrefix_TurkishLocale_negativeStillFalse() {
            // Locale.ROOT fix must not accidentally make everything match. Prove a
            // genuine non-match still returns false under tr-TR.
            List<QcRule> rules = List.of(new QcRule("SPECIMEN_ID_PREFIX", null, "QI-"));
            assertFalse(QcRuleEvaluator.isQcSample(rules, "SAMPLE-001", Map.of()),
                    "SPECIMEN_ID_PREFIX must still be a true prefix check under tr-TR");
        }

        @Test
        @DisplayName("FIELD_EQUALS unchanged — equalsIgnoreCase is already locale-safe by Java spec")
        void fieldEquals_TurkishLocale_stillMatches() {
            // String.equalsIgnoreCase is defined by Java spec to be locale-insensitive
            // (it uses per-char comparison, not full-string case-folding). This test
            // documents that guarantee and catches a future refactor that might
            // replace it with .equals(.toUpperCase()) style code.
            List<QcRule> rules = List.of(new QcRule("FIELD_EQUALS", "O.12", "INIT"));
            Map<String, String> fields = Map.of("O.12", "init");
            assertTrue(QcRuleEvaluator.isQcSample(rules, "SAMPLE001", fields),
                    "FIELD_EQUALS via equalsIgnoreCase must be locale-insensitive by Java spec");
        }
    }

    @Nested
    @DisplayName("Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("Empty rules list returns false")
        void emptyRules() {
            assertFalse(QcRuleEvaluator.isQcSample(List.of(), "SAMPLE", Map.of()));
        }

        @Test
        @DisplayName("Null rules list returns false")
        void nullRules() {
            assertFalse(QcRuleEvaluator.isQcSample(null, "SAMPLE", Map.of()));
        }

        @Test
        @DisplayName("Unknown rule type returns false")
        void unknownRuleType() {
            List<QcRule> rules = List.of(new QcRule("UNKNOWN_TYPE", null, "test"));
            assertFalse(QcRuleEvaluator.isQcSample(rules, "test", Map.of()));
        }

        @Test
        @DisplayName("Empty field values map returns false for field rules")
        void emptyFieldValuesMap() {
            List<QcRule> rules = List.of(new QcRule("FIELD_EQUALS", "O.12", "Q"));
            assertFalse(QcRuleEvaluator.isQcSample(rules, "SAMPLE", new HashMap<>()));
        }
    }
}
