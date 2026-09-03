package org.itech.ahb.fhir;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.itech.ahb.fhir.HL7ResultParser.ParsedResults;
import org.itech.ahb.profile.AstmResultRecordSelection;
import org.itech.ahb.profile.ControlResultRecognition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("ASTMResultParser control recognition")
class ASTMResultParserControlRecognitionTest {

    private static final AstmResultRecordSelection ALL_RESULTS = AstmResultRecordSelection.all();

    // O-record fixtures place the action code at O.12, following ASTM LIS2-A2.
    private static final String ASTM_QC_O12_Q =
            "H|\\^&|||Analyzer\r"
            + "P|1\r"
            + "O|1|QC_SAMPLE001|||||||||Q\r"
            + "R|1|^^^WBC|7.5|10*3/uL\r"
            + "L|1\r";

    private static final String ASTM_PATIENT_O12_P =
            "H|\\^&|||Analyzer\r"
            + "P|1\r"
            + "O|1|SAMPLE001|||||||||P\r"
            + "R|1|^^^WBC|7.5|10*3/uL\r"
            + "L|1\r";

    private static final String ASTM_QC_PREFIX =
            "H|\\^&|||Analyzer\r"
            + "P|1\r"
            + "O|1|QC-LOT-001|||||||||P\r"
            + "R|1|^^^WBC|7.5|10*3/uL\r"
            + "L|1\r";

    private static final String ASTM_TWO_RESULTS =
            "H|\\^&|||Analyzer\r"
            + "P|1\r"
            + "O|1|SAMPLE001|||||||||Q\r"
            + "R|1|^^^WBC|7.5|10*3/uL\r"
            + "R|2|^^^RBC|4.82|10*6/uL\r"
            + "L|1\r";

    private static List<String> lines(String raw) {
        return List.of(raw.split("[\\r\\n]+"));
    }

    @Nested
    @DisplayName("Rule-based QC detection")
    class RuleBased {

        @Test
        @DisplayName("FIELD_EQUALS O.12=Q flags results as control")
        void fieldEqualsO12Q_FlagsAsControl() {
            ControlResultRecognition recognition =
                    TestControlRecognitions.rule("FIELD_EQUALS", "O.12", "Q");
            ParsedResults parsed = ASTMResultParser.parse(
                    lines(ASTM_QC_O12_Q), recognition, ALL_RESULTS);

            assertNotNull(parsed);
            assertEquals("QC_SAMPLE001", parsed.accessionNumber());
            assertEquals(1, parsed.results().size());
            assertTrue(parsed.results().get(0).isControl());
        }

        @Test
        @DisplayName("SPECIMEN_ID_PREFIX matches accession, not action code")
        void specimenIdPrefix_FlagsAsControl() {
            ControlResultRecognition recognition =
                    TestControlRecognitions.rule("SPECIMEN_ID_PREFIX", null, "QC-");
            ParsedResults parsed = ASTMResultParser.parse(
                    lines(ASTM_QC_PREFIX), recognition, ALL_RESULTS);

            assertNotNull(parsed);
            assertEquals("QC-LOT-001", parsed.accessionNumber());
            assertEquals(1, parsed.results().size());
            assertTrue(parsed.results().get(0).isControl());
        }

        @Test
        @DisplayName("SPECIMEN_ID_PATTERN regex match flags as control")
        void specimenIdPattern_FlagsAsControl() {
            ControlResultRecognition recognition =
                    TestControlRecognitions.rule(
                            "SPECIMEN_ID_PATTERN", null, "^QC-LOT-\\d{3}$");
            ParsedResults parsed = ASTMResultParser.parse(
                    lines(ASTM_QC_PREFIX), recognition, ALL_RESULTS);

            assertNotNull(parsed);
            assertEquals("QC-LOT-001", parsed.accessionNumber());
            assertEquals(1, parsed.results().size());
            assertTrue(parsed.results().get(0).isControl());
        }

        @Test
        @DisplayName("Non-matching rules do not flag as control")
        void nonMatchingRules_NotControl() {
            ControlResultRecognition recognition =
                    TestControlRecognitions.rule("FIELD_EQUALS", "O.12", "Q");
            ParsedResults parsed = ASTMResultParser.parse(
                    lines(ASTM_PATIENT_O12_P), recognition, ALL_RESULTS);

            assertNotNull(parsed);
            assertFalse(parsed.results().get(0).isControl());
        }

        @Test
        @DisplayName("Multiple results all flagged when QC matches")
        void multipleResults_AllFlagged() {
            ControlResultRecognition recognition =
                    TestControlRecognitions.rule("FIELD_EQUALS", "O.12", "Q");
            ParsedResults parsed = ASTMResultParser.parse(
                    lines(ASTM_TWO_RESULTS), recognition, ALL_RESULTS);

            assertNotNull(parsed);
            assertEquals(2, parsed.results().size());
            assertTrue(parsed.results().get(0).isControl());
            assertTrue(parsed.results().get(1).isControl());
        }
    }

    @Nested
    @DisplayName("Explicit absence of control recognition")
    class NoRecognition {

        @Test
        @DisplayName("NONE does not classify O.12=Q")
        void noneDoesNotClassifyActionCode() {
            ParsedResults parsed = ASTMResultParser.parse(
                    lines(ASTM_QC_O12_Q), ControlResultRecognition.none(), ALL_RESULTS);

            assertNotNull(parsed);
            assertFalse(parsed.results().get(0).isControl());
        }
    }
}
