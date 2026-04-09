package org.itech.ahb.qc;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
