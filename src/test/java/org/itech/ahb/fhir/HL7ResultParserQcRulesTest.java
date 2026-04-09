package org.itech.ahb.fhir;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.itech.ahb.fhir.HL7ResultParser.ParsedResults;
import org.itech.ahb.qc.QcRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("HL7ResultParser — QC rules (FR-15)")
class HL7ResultParserQcRulesTest {

    private static List<String> segments(String... segs) {
        return List.of(segs);
    }

    @Nested
    @DisplayName("Rule-based QC detection")
    class RuleBased {

        @Test
        @DisplayName("OBR field rule flags all observations as control")
        void obrFieldRule_FlagsAllAsControl() {
            List<String> segs = segments(
                    "MSH|^~\\&|A|B|C|D|20260326||ORU^R01|1|P|2.3.1",
                    "PID|1||PAT001",
                    "OBR|1||ACC001|QC-PANEL",
                    "OBX|1|NM|WBC||7.5|10*3/uL",
                    "OBX|2|NM|RBC||4.82|10*6/uL");
            List<QcRule> rules = List.of(new QcRule("FIELD_EQUALS", "OBR.4", "QC-PANEL"));

            ParsedResults parsed = HL7ResultParser.parse(segs, rules);

            assertNotNull(parsed);
            assertEquals(2, parsed.results().size());
            assertTrue(parsed.results().get(0).isControl());
            assertTrue(parsed.results().get(1).isControl());
        }

        @Test
        @DisplayName("PID field rule flags as control")
        void pidFieldRule_FlagsAsControl() {
            List<String> segs = segments(
                    "MSH|^~\\&|A|B|C|D|20260326||ORU^R01|1|P|2.3.1",
                    "PID|1||QC-PATIENT",
                    "OBR|1||ACC001|CBC",
                    "OBX|1|NM|WBC||7.5|10*3/uL");
            List<QcRule> rules = List.of(new QcRule("FIELD_EQUALS", "PID.3", "QC-PATIENT"));

            ParsedResults parsed = HL7ResultParser.parse(segs, rules);

            assertNotNull(parsed);
            assertTrue(parsed.results().get(0).isControl());
        }

        @Test
        @DisplayName("SPECIMEN_ID_PREFIX on accession flags as control")
        void specimenIdPrefix_FlagsAsControl() {
            List<String> segs = segments(
                    "MSH|^~\\&|A|B|C|D|20260326||ORU^R01|1|P|2.3.1",
                    "PID|1||PAT001",
                    "OBR|1||QC-LOT-001|CBC",
                    "OBX|1|NM|WBC||7.5|10*3/uL");
            List<QcRule> rules = List.of(new QcRule("SPECIMEN_ID_PREFIX", null, "QC-"));

            ParsedResults parsed = HL7ResultParser.parse(segs, rules);

            assertNotNull(parsed);
            assertEquals("QC-LOT-001", parsed.accessionNumber());
            assertTrue(parsed.results().get(0).isControl());
        }

        @Test
        @DisplayName("FIELD_CONTAINS on OBR field flags as control")
        void fieldContainsOnObr_FlagsAsControl() {
            List<String> segs = segments(
                    "MSH|^~\\&|A|B|C|D|20260326||ORU^R01|1|P|2.3.1",
                    "PID|1||PAT001",
                    "OBR|1||ACC001|CTRL-CBC",
                    "OBX|1|NM|WBC||7.5|10*3/uL");
            List<QcRule> rules = List.of(new QcRule("FIELD_CONTAINS", "OBR.4", "CTRL"));

            ParsedResults parsed = HL7ResultParser.parse(segs, rules);

            assertNotNull(parsed);
            assertTrue(parsed.results().get(0).isControl());
        }

        @Test
        @DisplayName("SPECIMEN_ID_PATTERN regex on accession flags as control")
        void specimenIdPattern_FlagsAsControl() {
            List<String> segs = segments(
                    "MSH|^~\\&|A|B|C|D|20260326||ORU^R01|1|P|2.3.1",
                    "PID|1||PAT001",
                    "OBR|1||QC-LOT-001|CBC",
                    "OBX|1|NM|WBC||7.5|10*3/uL");
            List<QcRule> rules = List.of(new QcRule("SPECIMEN_ID_PATTERN", null, "^QC-LOT-\\d{3}$"));

            ParsedResults parsed = HL7ResultParser.parse(segs, rules);

            assertNotNull(parsed);
            assertTrue(parsed.results().get(0).isControl());
        }
    }

    @Nested
    @DisplayName("No fallback — HL7 had no QC before FR-15")
    class NoFallback {

        @Test
        @DisplayName("Null rules does not flag as control")
        void nullRules_NotControl() {
            List<String> segs = segments(
                    "MSH|^~\\&|A|B|C|D|20260326||ORU^R01|1|P|2.3.1",
                    "PID|1||QC-PATIENT",
                    "OBR|1||QC-LOT-001|QC-PANEL",
                    "OBX|1|NM|WBC||7.5|10*3/uL");

            ParsedResults parsed = HL7ResultParser.parse(segs, null);

            assertNotNull(parsed);
            assertFalse(parsed.results().get(0).isControl());
        }

        @Test
        @DisplayName("Empty rules does not flag as control")
        void emptyRules_NotControl() {
            List<String> segs = segments(
                    "MSH|^~\\&|A|B|C|D|20260326||ORU^R01|1|P|2.3.1",
                    "PID|1||PAT001",
                    "OBR|1||ACC001|CBC",
                    "OBX|1|NM|WBC||7.5|10*3/uL");

            ParsedResults parsed = HL7ResultParser.parse(segs, List.of());

            assertNotNull(parsed);
            assertFalse(parsed.results().get(0).isControl());
        }

        @Test
        @DisplayName("Non-matching rules do not flag as control")
        void nonMatchingRules_NotControl() {
            List<String> segs = segments(
                    "MSH|^~\\&|A|B|C|D|20260326||ORU^R01|1|P|2.3.1",
                    "PID|1||PAT001",
                    "OBR|1||ACC001|CBC",
                    "OBX|1|NM|WBC||7.5|10*3/uL");
            List<QcRule> rules = List.of(new QcRule("FIELD_EQUALS", "OBR.4", "QC-PANEL"));

            ParsedResults parsed = HL7ResultParser.parse(segs, rules);

            assertNotNull(parsed);
            assertFalse(parsed.results().get(0).isControl());
        }
    }

    @Nested
    @DisplayName("Field extraction correctness")
    class FieldExtraction {

        @Test
        @DisplayName("OBR fields indexed correctly")
        void obrFieldsIndexed() {
            List<String> segs = segments(
                    "MSH|^~\\&|A|B|C|D|20260326||ORU^R01|1|P|2.3.1",
                    "PID|1||PAT001",
                    "OBR|1|PLACER|FILLER|CBC",
                    "OBX|1|NM|WBC||7.5|10*3/uL");
            // OBR.1 = "1" (sequence number)
            List<QcRule> rules = List.of(new QcRule("FIELD_EQUALS", "OBR.1", "1"));

            ParsedResults parsed = HL7ResultParser.parse(segs, rules);

            assertNotNull(parsed);
            assertTrue(parsed.results().get(0).isControl());
        }

        @Test
        @DisplayName("PID fields indexed correctly — PID.5 patient name")
        void pidFieldsIndexed() {
            List<String> segs = segments(
                    "MSH|^~\\&|A|B|C|D|20260326||ORU^R01|1|P|2.3.1",
                    "PID|1|EXT|INT|ALT|DOE^JOHN",
                    "OBR|1||ACC001|CBC",
                    "OBX|1|NM|WBC||7.5|10*3/uL");
            List<QcRule> rules = List.of(new QcRule("FIELD_CONTAINS", "PID.5", "DOE"));

            ParsedResults parsed = HL7ResultParser.parse(segs, rules);

            assertNotNull(parsed);
            assertTrue(parsed.results().get(0).isControl());
        }
    }
}
