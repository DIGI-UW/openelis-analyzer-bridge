package org.itech.ahb.fhir;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.itech.ahb.fhir.HL7ResultParser.ParsedResults;
import org.itech.ahb.qc.QcRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("ASTMResultParser — QC rules (FR-15)")
class ASTMResultParserQcRulesTest {

    private static final String ASTM_QC_O12_Q =
            "H|\\^&|||Analyzer\r"
            + "P|1\r"
            + "O|1|QC_SAMPLE001||||||||||Q\r"
            + "R|1|^^^WBC|7.5|10*3/uL\r"
            + "L|1\r";

    private static final String ASTM_PATIENT_O12_P =
            "H|\\^&|||Analyzer\r"
            + "P|1\r"
            + "O|1|SAMPLE001||||||||||P\r"
            + "R|1|^^^WBC|7.5|10*3/uL\r"
            + "L|1\r";

    private static final String ASTM_QC_PREFIX =
            "H|\\^&|||Analyzer\r"
            + "P|1\r"
            + "O|1|QC-LOT-001||||||||||P\r"
            + "R|1|^^^WBC|7.5|10*3/uL\r"
            + "L|1\r";

    private static final String ASTM_TWO_RESULTS =
            "H|\\^&|||Analyzer\r"
            + "P|1\r"
            + "O|1|SAMPLE001||||||||||Q\r"
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
            List<QcRule> rules = List.of(new QcRule("FIELD_EQUALS", "O.12", "Q"));
            ParsedResults parsed = ASTMResultParser.parse(lines(ASTM_QC_O12_Q), rules);

            assertNotNull(parsed);
            assertEquals("QC_SAMPLE001", parsed.accessionNumber());
            assertEquals(1, parsed.results().size());
            assertTrue(parsed.results().get(0).isControl());
        }

        @Test
        @DisplayName("SPECIMEN_ID_PREFIX matches accession, not action code")
        void specimenIdPrefix_FlagsAsControl() {
            List<QcRule> rules = List.of(new QcRule("SPECIMEN_ID_PREFIX", null, "QC-"));
            ParsedResults parsed = ASTMResultParser.parse(lines(ASTM_QC_PREFIX), rules);

            assertNotNull(parsed);
            assertEquals("QC-LOT-001", parsed.accessionNumber());
            assertEquals(1, parsed.results().size());
            assertTrue(parsed.results().get(0).isControl());
        }

        @Test
        @DisplayName("SPECIMEN_ID_PATTERN regex match flags as control")
        void specimenIdPattern_FlagsAsControl() {
            List<QcRule> rules = List.of(new QcRule("SPECIMEN_ID_PATTERN", null, "^QC-LOT-\\d{3}$"));
            ParsedResults parsed = ASTMResultParser.parse(lines(ASTM_QC_PREFIX), rules);

            assertNotNull(parsed);
            assertEquals("QC-LOT-001", parsed.accessionNumber());
            assertEquals(1, parsed.results().size());
            assertTrue(parsed.results().get(0).isControl());
        }

        @Test
        @DisplayName("Non-matching rules do not flag as control")
        void nonMatchingRules_NotControl() {
            List<QcRule> rules = List.of(new QcRule("FIELD_EQUALS", "O.12", "Q"));
            ParsedResults parsed = ASTMResultParser.parse(lines(ASTM_PATIENT_O12_P), rules);

            assertNotNull(parsed);
            assertFalse(parsed.results().get(0).isControl());
        }

        @Test
        @DisplayName("Multiple results all flagged when QC matches")
        void multipleResults_AllFlagged() {
            List<QcRule> rules = List.of(new QcRule("FIELD_EQUALS", "O.12", "Q"));
            ParsedResults parsed = ASTMResultParser.parse(lines(ASTM_TWO_RESULTS), rules);

            assertNotNull(parsed);
            assertEquals(2, parsed.results().size());
            assertTrue(parsed.results().get(0).isControl());
            assertTrue(parsed.results().get(1).isControl());
        }
    }

    @Nested
    @DisplayName("Fallback to hardcoded O.12==Q")
    class Fallback {

        @Test
        @DisplayName("Null rules falls back to hardcoded O.12 check — QC detected")
        void nullRules_FallbackDetectsQc() {
            ParsedResults parsed = ASTMResultParser.parse(lines(ASTM_QC_O12_Q), null);

            assertNotNull(parsed);
            assertTrue(parsed.results().get(0).isControl());
        }

        @Test
        @DisplayName("Empty rules falls back to hardcoded O.12 check — QC detected")
        void emptyRules_FallbackDetectsQc() {
            ParsedResults parsed = ASTMResultParser.parse(lines(ASTM_QC_O12_Q), List.of());

            assertNotNull(parsed);
            assertTrue(parsed.results().get(0).isControl());
        }

        @Test
        @DisplayName("Null rules with O.12=P — not control")
        void nullRules_PatientNotControl() {
            ParsedResults parsed = ASTMResultParser.parse(lines(ASTM_PATIENT_O12_P), null);

            assertNotNull(parsed);
            assertFalse(parsed.results().get(0).isControl());
        }
    }
}
