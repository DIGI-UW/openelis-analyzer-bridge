package org.itech.ahb.fhir;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.itech.ahb.fhir.FhirBundleBuilder.AnalyzerResult;
import org.itech.ahb.fhir.HL7ResultParser.ParsedResults;
import org.itech.ahb.profile.ControlResultRecognition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link HL7ResultParser}.
 *
 * <p>Validates HL7 v2 ORU^R01 parsing logic: accession extraction from
 * OBR-2/OBR-3, test code extraction from OBX-3, value/units from OBX-5/OBX-6,
 * and value type detection from OBX-2.
 */
@DisplayName("HL7ResultParser")
class HL7ResultParserTest {

    // Realistic HL7 ORU^R01 from a Mindray BC-5380 hematology analyzer
    private static final String VALID_ORU_MESSAGE =
            "MSH|^~\\&|MINDRAY|BC-5380|OpenELIS|LAB|20260326||ORU^R01|MSG001|P|2.3.1\r"
            + "PID|1||PAT001\r"
            + "OBR|1|PLACER123|FILLER456|CBC\r"
            + "OBX|1|NM|WBC||7.5|10*3/uL\r"
            + "OBX|2|NM|RBC||4.82|10*6/uL\r"
            + "OBX|3|ST|INTERP||Normal\r";

    @Nested
    @DisplayName("parseRaw — valid ORU^R01 message")
    class ValidMessage {

        @Test
        @DisplayName("Should extract accession from OBR-3 filler order number")
        void shouldExtractAccessionFromFillerOrderNumber() {
            ParsedResults parsed = HL7ResultParser.parseRaw(VALID_ORU_MESSAGE, ControlResultRecognition.none());

            assertNotNull(parsed);
            assertEquals("FILLER456", parsed.accessionNumber());
        }

        @Test
        @DisplayName("Should extract correct number of results from OBX segments")
        void shouldExtractAllResults() {
            ParsedResults parsed = HL7ResultParser.parseRaw(VALID_ORU_MESSAGE, ControlResultRecognition.none());

            assertNotNull(parsed);
            assertEquals(3, parsed.results().size());
        }

        @Test
        @DisplayName("Should extract test codes from OBX-3")
        void shouldExtractTestCodes() {
            ParsedResults parsed = HL7ResultParser.parseRaw(VALID_ORU_MESSAGE, ControlResultRecognition.none());

            assertNotNull(parsed);
            List<String> codes = parsed.results().stream()
                    .map(AnalyzerResult::testCode).toList();
            assertEquals(List.of("WBC", "RBC", "INTERP"), codes);
        }

        @Test
        @DisplayName("Should extract values from OBX-5")
        void shouldExtractValues() {
            ParsedResults parsed = HL7ResultParser.parseRaw(VALID_ORU_MESSAGE, ControlResultRecognition.none());

            assertNotNull(parsed);
            List<String> values = parsed.results().stream()
                    .map(AnalyzerResult::value).toList();
            assertEquals(List.of("7.5", "4.82", "Normal"), values);
        }

        @Test
        @DisplayName("Should extract units from OBX-6")
        void shouldExtractUnits() {
            ParsedResults parsed = HL7ResultParser.parseRaw(VALID_ORU_MESSAGE, ControlResultRecognition.none());

            assertNotNull(parsed);
            assertEquals("10*3/uL", parsed.results().get(0).units());
            assertEquals("10*6/uL", parsed.results().get(1).units());
        }
    }

    @Nested
    @DisplayName("OBX-3 test code extraction")
    class TestCodeExtraction {

        @Test
        @DisplayName("Simple format 'WBC' - component 1 extracted")
        void simpleFormatExtractsComponent1() {
            String msg = "MSH|^~\\&|A|B|C|D|20260326||ORU^R01|1|P|2.3.1\r"
                    + "OBR|1||ACC001|CBC\r"
                    + "OBX|1|NM|WBC||5.0|10*3/uL\r";

            ParsedResults parsed = HL7ResultParser.parseRaw(msg, ControlResultRecognition.none());

            assertNotNull(parsed);
            assertEquals("WBC", parsed.results().get(0).testCode());
        }

        @Test
        @DisplayName("Complex format '^^^WBC^White Blood Cells' - component 4 extracted")
        void complexFormatExtractsComponent4() {
            String msg = "MSH|^~\\&|A|B|C|D|20260326||ORU^R01|1|P|2.3.1\r"
                    + "OBR|1||ACC001|CBC\r"
                    + "OBX|1|NM|^^^WBC^White Blood Cells||5.0|10*3/uL\r";

            ParsedResults parsed = HL7ResultParser.parseRaw(msg, ControlResultRecognition.none());

            assertNotNull(parsed);
            assertEquals("WBC", parsed.results().get(0).testCode());
        }
    }

    @Nested
    @DisplayName("OBR accession extraction")
    class AccessionExtraction {

        @Test
        @DisplayName("OBR-3 filler order number preferred over OBR-2 placer")
        void fillerPreferredOverPlacer() {
            String msg = "MSH|^~\\&|A|B|C|D|20260326||ORU^R01|1|P|2.3.1\r"
                    + "OBR|1|PLACER999|FILLER888|CBC\r"
                    + "OBX|1|NM|WBC||5.0|10*3/uL\r";

            ParsedResults parsed = HL7ResultParser.parseRaw(msg, ControlResultRecognition.none());

            assertNotNull(parsed);
            assertEquals("FILLER888", parsed.accessionNumber());
        }

        @Test
        @DisplayName("Falls back to OBR-2 placer when OBR-3 is empty")
        void fallbackToPlacerWhenFillerEmpty() {
            String msg = "MSH|^~\\&|A|B|C|D|20260326||ORU^R01|1|P|2.3.1\r"
                    + "OBR|1|PLACER999||CBC\r"
                    + "OBX|1|NM|WBC||5.0|10*3/uL\r";

            ParsedResults parsed = HL7ResultParser.parseRaw(msg, ControlResultRecognition.none());

            assertNotNull(parsed);
            assertEquals("PLACER999", parsed.accessionNumber());
        }

        @Test
        @DisplayName("Falls back to PID-3 when OBR has no accession")
        void fallbackToPid3WhenNoObrAccession() {
            String msg = "MSH|^~\\&|A|B|C|D|20260326||ORU^R01|1|P|2.3.1\r"
                    + "PID|1||PATIENT_ID_123\r"
                    + "OBR|1|||CBC\r"
                    + "OBX|1|NM|WBC||5.0|10*3/uL\r";

            ParsedResults parsed = HL7ResultParser.parseRaw(msg, ControlResultRecognition.none());

            assertNotNull(parsed);
            assertEquals("PATIENT_ID_123", parsed.accessionNumber());
        }

        @Test
        @DisplayName("Uses 'HL7-UNKNOWN' when no accession source available")
        void unknownWhenNoAccessionSource() {
            String msg = "MSH|^~\\&|A|B|C|D|20260326||ORU^R01|1|P|2.3.1\r"
                    + "OBR|1|||CBC\r"
                    + "OBX|1|NM|WBC||5.0|10*3/uL\r";

            ParsedResults parsed = HL7ResultParser.parseRaw(msg, ControlResultRecognition.none());

            assertNotNull(parsed);
            assertEquals("HL7-UNKNOWN", parsed.accessionNumber());
        }
    }

    @Nested
    @DisplayName("Value type detection")
    class ValueTypeDetection {

        @Test
        @DisplayName("OBX-2 = 'NM' -> isNumeric = true")
        void numericTypeDetected() {
            String msg = "MSH|^~\\&|A|B|C|D|20260326||ORU^R01|1|P|2.3.1\r"
                    + "OBR|1||ACC001|CBC\r"
                    + "OBX|1|NM|WBC||7.5|10*3/uL\r";

            ParsedResults parsed = HL7ResultParser.parseRaw(msg, ControlResultRecognition.none());

            assertNotNull(parsed);
            assertTrue(parsed.results().get(0).isNumeric());
        }

        @Test
        @DisplayName("OBX-2 = 'ST' -> isNumeric = false")
        void stringTypeDetected() {
            String msg = "MSH|^~\\&|A|B|C|D|20260326||ORU^R01|1|P|2.3.1\r"
                    + "OBR|1||ACC001|CBC\r"
                    + "OBX|1|ST|INTERP||Normal|\r";

            ParsedResults parsed = HL7ResultParser.parseRaw(msg, ControlResultRecognition.none());

            assertNotNull(parsed);
            assertFalse(parsed.results().get(0).isNumeric());
        }

        @Test
        @DisplayName("OBX-2 = 'SN' -> isNumeric = true")
        void structuredNumericTypeDetected() {
            String msg = "MSH|^~\\&|A|B|C|D|20260326||ORU^R01|1|P|2.3.1\r"
                    + "OBR|1||ACC001|CBC\r"
                    + "OBX|1|SN|RATIO||1.5|ratio\r";

            ParsedResults parsed = HL7ResultParser.parseRaw(msg, ControlResultRecognition.none());

            assertNotNull(parsed);
            assertTrue(parsed.results().get(0).isNumeric());
        }
    }

    @Nested
    @DisplayName("Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("Empty OBX-5 value -> result skipped")
        void emptyValueSkipped() {
            String msg = "MSH|^~\\&|A|B|C|D|20260326||ORU^R01|1|P|2.3.1\r"
                    + "OBR|1||ACC001|CBC\r"
                    + "OBX|1|NM|WBC||7.5|10*3/uL\r"
                    + "OBX|2|NM|RBC|||10*6/uL\r";

            ParsedResults parsed = HL7ResultParser.parseRaw(msg, ControlResultRecognition.none());

            assertNotNull(parsed);
            assertEquals(1, parsed.results().size());
            assertEquals("WBC", parsed.results().get(0).testCode());
        }

        @Test
        @DisplayName("Null input -> returns null")
        void nullInputReturnsNull() {
            assertNull(HL7ResultParser.parseRaw(null, ControlResultRecognition.none()));
        }

        @Test
        @DisplayName("Empty input -> returns null")
        void emptyInputReturnsNull() {
            assertNull(HL7ResultParser.parseRaw("", ControlResultRecognition.none()));
        }

        @Test
        @DisplayName("Blank input -> returns null")
        void blankInputReturnsNull() {
            assertNull(HL7ResultParser.parseRaw("   ", ControlResultRecognition.none()));
        }

        @Test
        @DisplayName("Message with no OBX segments -> returns null")
        void noObxReturnsNull() {
            String msg = "MSH|^~\\&|A|B|C|D|20260326||ORU^R01|1|P|2.3.1\r"
                    + "OBR|1||ACC001|CBC\r";

            assertNull(HL7ResultParser.parseRaw(msg, ControlResultRecognition.none()));
        }

        @Test
        @DisplayName("Message with \\n line terminators parsed correctly")
        void newlineTerminatorsHandled() {
            String msg = "MSH|^~\\&|A|B|C|D|20260326||ORU^R01|1|P|2.3.1\n"
                    + "OBR|1||ACC001|CBC\n"
                    + "OBX|1|NM|WBC||5.0|10*3/uL\n";

            ParsedResults parsed = HL7ResultParser.parseRaw(msg, ControlResultRecognition.none());

            assertNotNull(parsed);
            assertEquals(1, parsed.results().size());
        }

        @Test
        @DisplayName("Message with \\r\\n line terminators parsed correctly")
        void crlfTerminatorsHandled() {
            String msg = "MSH|^~\\&|A|B|C|D|20260326||ORU^R01|1|P|2.3.1\r\n"
                    + "OBR|1||ACC001|CBC\r\n"
                    + "OBX|1|NM|WBC||5.0|10*3/uL\r\n";

            ParsedResults parsed = HL7ResultParser.parseRaw(msg, ControlResultRecognition.none());

            assertNotNull(parsed);
            assertEquals(1, parsed.results().size());
        }

        @Test
        @DisplayName("Unit field with subcomponents extracts first component")
        void unitSubcomponentsHandled() {
            String msg = "MSH|^~\\&|A|B|C|D|20260326||ORU^R01|1|P|2.3.1\r"
                    + "OBR|1||ACC001|CBC\r"
                    + "OBX|1|NM|WBC||7.5|10*3/uL^10^3^uL\r";

            ParsedResults parsed = HL7ResultParser.parseRaw(msg, ControlResultRecognition.none());

            assertNotNull(parsed);
            assertEquals("10*3/uL", parsed.results().get(0).units());
        }
    }
}
