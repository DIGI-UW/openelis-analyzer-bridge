package org.itech.ahb.fhir;

import static org.junit.jupiter.api.Assertions.*;

import org.itech.ahb.profile.ControlResultRecognition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("FileResultParser control recognition")
class FileResultParserControlRecognitionTest {

    @Nested
    @DisplayName("Profile rules")
    class IsControlRowWithRules {

        @Test
        @DisplayName("FIELD_EQUALS on QC_TASK matches")
        void fieldEquals_QcTask() {
            ControlResultRecognition recognition =
                    TestControlRecognitions.rule(
                            "FIELD_EQUALS", "QC_TASK", "CONTROL");
            assertTrue(FileResultParser.isControlRow(
                    "PATIENT-001", "CONTROL", recognition));
        }

        @Test
        @DisplayName("SPECIMEN_ID_PREFIX matches on sample ID")
        void specimenIdPrefix_Matches() {
            ControlResultRecognition recognition =
                    TestControlRecognitions.rule("SPECIMEN_ID_PREFIX", null, "NEG");
            assertTrue(FileResultParser.isControlRow("NEG-001", null, recognition));
        }

        @Test
        @DisplayName("SPECIMEN_ID_PATTERN regex matches")
        void specimenIdPattern_Matches() {
            ControlResultRecognition recognition =
                    TestControlRecognitions.rule(
                            "SPECIMEN_ID_PATTERN", null, "^(CNEG|CPOS|NTC).*");
            assertTrue(FileResultParser.isControlRow(
                    "CPOS-2026", null, recognition));
        }

        @Test
        @DisplayName("FIELD_CONTAINS on QC_TASK substring matches")
        void fieldContains_QcTask() {
            ControlResultRecognition recognition =
                    TestControlRecognitions.rule(
                            "FIELD_CONTAINS", "QC_TASK", "CTRL");
            assertTrue(FileResultParser.isControlRow(
                    "SAMPLE-001", "Internal CTRL Check", recognition));
        }

        @Test
        @DisplayName("Non-matching rules return false")
        void nonMatchingRules() {
            ControlResultRecognition recognition =
                    TestControlRecognitions.rule(
                            "FIELD_EQUALS", "QC_TASK", "CONTROL");
            assertFalse(FileResultParser.isControlRow(
                    "PATIENT-001", "UNKNOWN", recognition));
        }
    }

    @Nested
    @DisplayName("Only profile rules classify controls")
    class ProfileRulesAreAuthoritative {

        @Test
        @DisplayName("An unmatched specimen ID stays a patient result")
        void unmatchedSpecimenIdStaysPatient() {
            ControlResultRecognition recognition =
                    TestControlRecognitions.rule(
                            "FIELD_EQUALS", "QC_TASK", "CUSTOM_QC");
            assertFalse(FileResultParser.isControlRow(
                    "NEG", "PATIENT", recognition));
        }

        @Test
        @DisplayName("An unmatched rule never triggers another classifier")
        void unmatchedRuleDoesNotTriggerAnotherClassifier() {
            ControlResultRecognition recognition =
                    TestControlRecognitions.rule(
                            "SPECIMEN_ID_PREFIX", null, "CUSTOM-");
            assertFalse(FileResultParser.isControlRow("NEG", null, recognition));
        }
    }

    @Nested
    @DisplayName("Explicit absence of control recognition")
    class NoRecognition {

        @Test
        @DisplayName("NONE never classifies from task or specimen ID alone")
        void noneDoesNotClassifyTaskOrSpecimenId() {
            ControlResultRecognition recognition = ControlResultRecognition.none();

            assertFalse(FileResultParser.isControlRow("CNEG-2026", null, recognition));
            assertFalse(FileResultParser.isControlRow("POS-CTRL", "CONTROL", recognition));
        }
    }
}
