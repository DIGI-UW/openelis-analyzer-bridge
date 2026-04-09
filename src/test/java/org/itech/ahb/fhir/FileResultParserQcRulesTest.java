package org.itech.ahb.fhir;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.itech.ahb.qc.QcRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("FileResultParser — QC rules (FR-15)")
class FileResultParserQcRulesTest {

    @Nested
    @DisplayName("isControlRow with QC rules")
    class IsControlRowWithRules {

        @Test
        @DisplayName("FIELD_EQUALS on QC_TASK matches")
        void fieldEquals_QcTask() {
            List<QcRule> rules = List.of(new QcRule("FIELD_EQUALS", "QC_TASK", "CONTROL"));
            assertTrue(FileResultParser.isControlRow("PATIENT-001", "CONTROL", rules));
        }

        @Test
        @DisplayName("SPECIMEN_ID_PREFIX matches on sample ID")
        void specimenIdPrefix_Matches() {
            List<QcRule> rules = List.of(new QcRule("SPECIMEN_ID_PREFIX", null, "NEG"));
            assertTrue(FileResultParser.isControlRow("NEG-001", null, rules));
        }

        @Test
        @DisplayName("SPECIMEN_ID_PATTERN regex matches")
        void specimenIdPattern_Matches() {
            List<QcRule> rules = List.of(new QcRule("SPECIMEN_ID_PATTERN", null, "^(CNEG|CPOS|NTC).*"));
            assertTrue(FileResultParser.isControlRow("CPOS-2026", null, rules));
        }

        @Test
        @DisplayName("FIELD_CONTAINS on QC_TASK substring matches")
        void fieldContains_QcTask() {
            List<QcRule> rules = List.of(new QcRule("FIELD_CONTAINS", "QC_TASK", "CTRL"));
            assertTrue(FileResultParser.isControlRow("SAMPLE-001", "Internal CTRL Check", rules));
        }

        @Test
        @DisplayName("Non-matching rules return false")
        void nonMatchingRules() {
            List<QcRule> rules = List.of(new QcRule("FIELD_EQUALS", "QC_TASK", "CONTROL"));
            assertFalse(FileResultParser.isControlRow("PATIENT-001", "UNKNOWN", rules));
        }
    }

    @Nested
    @DisplayName("Rules override hardcoded fallback")
    class RulesOverrideFallback {

        @Test
        @DisplayName("Rules present — hardcoded prefixes bypassed entirely")
        void rulesPresent_HardcodedPrefixesBypassed() {
            // "NEG" would match hardcoded CONTROL_PREFIXES, but rules take precedence
            List<QcRule> rules = List.of(new QcRule("FIELD_EQUALS", "QC_TASK", "CUSTOM_QC"));
            // qcTask="PATIENT" does not match the rule, and hardcoded prefix check is NOT run
            assertFalse(FileResultParser.isControlRow("NEG", "PATIENT", rules));
        }

        @Test
        @DisplayName("Rules present — hardcoded prefix list not consulted")
        void rulesPresent_FallbackPrefixesIgnored() {
            // "NEG" matches hardcoded CONTROL_PREFIXES but does NOT start with "CUSTOM-"
            List<QcRule> rules = List.of(new QcRule("SPECIMEN_ID_PREFIX", null, "CUSTOM-"));
            assertFalse(FileResultParser.isControlRow("NEG", null, rules));
        }
    }

    @Nested
    @DisplayName("Fallback when rules absent")
    class FallbackWhenRulesAbsent {

        @Test
        @DisplayName("Null rules — falls back to hardcoded prefixes")
        void nullRules_FallbackPrefixes() {
            assertTrue(FileResultParser.isControlRow("CNEG-2026", null, null));
        }

        @Test
        @DisplayName("Empty rules — falls back to hardcoded prefixes")
        void emptyRules_FallbackPrefixes() {
            assertTrue(FileResultParser.isControlRow("POS-CTRL", null, List.of()));
        }

        @Test
        @DisplayName("Null rules — falls back to qcTask=CONTROL")
        void nullRules_FallbackQcTask() {
            assertTrue(FileResultParser.isControlRow("PATIENT-001", "CONTROL", null));
        }

        @Test
        @DisplayName("Null rules — patient sample returns false")
        void nullRules_PatientSample() {
            assertFalse(FileResultParser.isControlRow("PATIENT-001", "UNKNOWN", null));
        }

        @Test
        @DisplayName("Empty rules — patient sample returns false")
        void emptyRules_PatientSample() {
            assertFalse(FileResultParser.isControlRow("PATIENT-001", null, List.of()));
        }
    }
}
