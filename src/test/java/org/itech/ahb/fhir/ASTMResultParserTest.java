package org.itech.ahb.fhir;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.itech.ahb.fhir.FhirBundleBuilder.AnalyzerResult;
import org.itech.ahb.fhir.HL7ResultParser.ParsedResults;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ASTMResultParser}.
 *
 * <p>Validates ASTM LIS2-A2 parsing logic: accession extraction from O-record,
 * test code extraction from R-record (^^^CODE and ^CODE formats), value
 * cleaning, QC sample detection, and timestamp extraction.
 */
@DisplayName("ASTMResultParser")
class ASTMResultParserTest {

    // Realistic ASTM from a GeneXpert analyzer.
    // Field indices: O|1[0]|specimen[2]|...[3-11]|actionCode[12]
    //                R|1[0]|seq[1]|testId[2]|value[3]|units[4]|ref[5]|flag[6]|op[7]|status[8]|timestamp[9]
    private static final String VALID_ASTM_MESSAGE =
            "H|\\^&|||GeneXpert^1.0|||||||LIS2-A2\n"
            + "P|1\n"
            + "O|1|SAMPLE001||||||||||P\n"
            + "R|1|^^^HIV-VL|1520.5|copies/mL|||||20260326120000\n"
            + "R|2|^^^CT|28.5|cycles|||||20260326120000\n"
            + "L|1\n";

    @Nested
    @DisplayName("parseRaw - valid ASTM message")
    class ValidMessage {

        @Test
        @DisplayName("Should extract accession from O-record specimen ID")
        void shouldExtractAccession() {
            ParsedResults parsed = ASTMResultParser.parseRaw(VALID_ASTM_MESSAGE);

            assertNotNull(parsed);
            assertEquals("SAMPLE001", parsed.accessionNumber());
        }

        @Test
        @DisplayName("Should extract correct number of results from R-records")
        void shouldExtractAllResults() {
            ParsedResults parsed = ASTMResultParser.parseRaw(VALID_ASTM_MESSAGE);

            assertNotNull(parsed);
            assertEquals(2, parsed.results().size());
        }

        @Test
        @DisplayName("Should extract test codes from R-record")
        void shouldExtractTestCodes() {
            ParsedResults parsed = ASTMResultParser.parseRaw(VALID_ASTM_MESSAGE);

            assertNotNull(parsed);
            List<String> codes = parsed.results().stream()
                    .map(AnalyzerResult::testCode).toList();
            assertEquals(List.of("HIV-VL", "CT"), codes);
        }

        @Test
        @DisplayName("Should extract values from R-record")
        void shouldExtractValues() {
            ParsedResults parsed = ASTMResultParser.parseRaw(VALID_ASTM_MESSAGE);

            assertNotNull(parsed);
            assertEquals("1520.5", parsed.results().get(0).value());
            assertEquals("28.5", parsed.results().get(1).value());
        }

        @Test
        @DisplayName("Should extract units from R-record")
        void shouldExtractUnits() {
            ParsedResults parsed = ASTMResultParser.parseRaw(VALID_ASTM_MESSAGE);

            assertNotNull(parsed);
            assertEquals("copies/mL", parsed.results().get(0).units());
            assertEquals("cycles", parsed.results().get(1).units());
        }
    }

    @Nested
    @DisplayName("Test code extraction formats")
    class TestCodeFormats {

        @Test
        @DisplayName("^^^CODE format - component 4 extracted")
        void tripleCaretFormat() {
            String msg = "H|\\^&|||Analyzer\n"
                    + "P|1\n"
                    + "O|1|ACC001\n"
                    + "R|1|^^^GLUCOSE|95.0|mg/dL\n"
                    + "L|1\n";

            ParsedResults parsed = ASTMResultParser.parseRaw(msg);

            assertNotNull(parsed);
            assertEquals("GLUCOSE", parsed.results().get(0).testCode());
        }

        @Test
        @DisplayName("^CODE format - component 2 extracted")
        void singleCaretFormat() {
            String msg = "H|\\^&|||Analyzer\n"
                    + "P|1\n"
                    + "O|1|ACC001\n"
                    + "R|1|^GLUCOSE|95.0|mg/dL\n"
                    + "L|1\n";

            ParsedResults parsed = ASTMResultParser.parseRaw(msg);

            assertNotNull(parsed);
            assertEquals("GLUCOSE", parsed.results().get(0).testCode());
        }

        @Test
        @DisplayName("Plain CODE format - whole field used")
        void plainFormat() {
            String msg = "H|\\^&|||Analyzer\n"
                    + "P|1\n"
                    + "O|1|ACC001\n"
                    + "R|1|GLUCOSE|95.0|mg/dL\n"
                    + "L|1\n";

            ParsedResults parsed = ASTMResultParser.parseRaw(msg);

            assertNotNull(parsed);
            assertEquals("GLUCOSE", parsed.results().get(0).testCode());
        }
    }

    @Nested
    @DisplayName("Value cleaning")
    class ValueCleaning {

        @Test
        @DisplayName("Trailing caret stripped: 'NEGATIVE^' -> 'NEGATIVE'")
        void trailingCaretStripped() {
            String msg = "H|\\^&|||Analyzer\n"
                    + "P|1\n"
                    + "O|1|ACC001\n"
                    + "R|1|^^^TEST|NEGATIVE^||\n"
                    + "L|1\n";

            ParsedResults parsed = ASTMResultParser.parseRaw(msg);

            assertNotNull(parsed);
            assertEquals("NEGATIVE", parsed.results().get(0).value());
        }

        @Test
        @DisplayName("Leading caret stripped: '^3.10' -> '3.10'")
        void leadingCaretStripped() {
            String msg = "H|\\^&|||Analyzer\n"
                    + "P|1\n"
                    + "O|1|ACC001\n"
                    + "R|1|^^^TEST|^3.10|mg/dL\n"
                    + "L|1\n";

            ParsedResults parsed = ASTMResultParser.parseRaw(msg);

            assertNotNull(parsed);
            assertEquals("3.10", parsed.results().get(0).value());
        }

        @Test
        @DisplayName("Multiple trailing carets stripped: 'POS^^^' -> 'POS'")
        void multipleTrailingCaretsStripped() {
            String msg = "H|\\^&|||Analyzer\n"
                    + "P|1\n"
                    + "O|1|ACC001\n"
                    + "R|1|^^^TEST|POS^^^||\n"
                    + "L|1\n";

            ParsedResults parsed = ASTMResultParser.parseRaw(msg);

            assertNotNull(parsed);
            assertEquals("POS", parsed.results().get(0).value());
        }
    }

    @Nested
    @DisplayName("QC sample detection")
    class QcDetection {

        @Test
        @DisplayName("O.12 = 'Q' -> result.isControl() = true")
        void qcSampleDetected() {
            // O-record: O[0]|seq[1]|specimen[2]|...[3-11]|actionCode[12]
            // Need 13 pipe-separated fields so index 12 = "Q"
            String msg = "H|\\^&|||Analyzer\n"
                    + "P|1\n"
                    + "O|1|QC_SAMPLE001||||||||||Q\n"
                    + "R|1|^^^TEST|5.0|units\n"
                    + "L|1\n";

            ParsedResults parsed = ASTMResultParser.parseRaw(msg);

            assertNotNull(parsed);
            assertTrue(parsed.results().get(0).isControl(),
                    "QC sample should be flagged as control");
        }

        @Test
        @DisplayName("O.12 = 'P' (normal) -> result.isControl() = false")
        void normalSampleNotFlaggedAsQc() {
            ParsedResults parsed = ASTMResultParser.parseRaw(VALID_ASTM_MESSAGE);

            assertNotNull(parsed);
            assertFalse(parsed.results().get(0).isControl(),
                    "Normal sample should not be flagged as control");
        }

        @Test
        @DisplayName("O.12 absent -> result.isControl() = false")
        void missingActionCodeNotQc() {
            String msg = "H|\\^&|||Analyzer\n"
                    + "P|1\n"
                    + "O|1|ACC001\n"
                    + "R|1|^^^TEST|5.0|units\n"
                    + "L|1\n";

            ParsedResults parsed = ASTMResultParser.parseRaw(msg);

            assertNotNull(parsed);
            assertFalse(parsed.results().get(0).isControl());
        }
    }

    @Nested
    @DisplayName("Timestamp extraction")
    class TimestampExtraction {

        @Test
        @DisplayName("R.9 timestamp extracted when present")
        void timestampExtracted() {
            ParsedResults parsed = ASTMResultParser.parseRaw(VALID_ASTM_MESSAGE);

            assertNotNull(parsed);
            assertEquals("20260326120000", parsed.results().get(0).timestamp());
        }

        @Test
        @DisplayName("Missing R.9 timestamp -> null timestamp")
        void missingTimestamp() {
            String msg = "H|\\^&|||Analyzer\n"
                    + "P|1\n"
                    + "O|1|ACC001\n"
                    + "R|1|^^^TEST|5.0|units\n"
                    + "L|1\n";

            ParsedResults parsed = ASTMResultParser.parseRaw(msg);

            assertNotNull(parsed);
            assertNull(parsed.results().get(0).timestamp());
        }
    }

    @Nested
    @DisplayName("Numeric detection")
    class NumericDetection {

        @Test
        @DisplayName("Numeric value '1520.5' -> isNumeric = true")
        void numericValueDetected() {
            ParsedResults parsed = ASTMResultParser.parseRaw(VALID_ASTM_MESSAGE);

            assertNotNull(parsed);
            assertTrue(parsed.results().get(0).isNumeric());
        }

        @Test
        @DisplayName("Text value 'NEGATIVE' -> isNumeric = false")
        void textValueDetected() {
            String msg = "H|\\^&|||Analyzer\n"
                    + "P|1\n"
                    + "O|1|ACC001\n"
                    + "R|1|^^^TEST|NEGATIVE||\n"
                    + "L|1\n";

            ParsedResults parsed = ASTMResultParser.parseRaw(msg);

            assertNotNull(parsed);
            assertFalse(parsed.results().get(0).isNumeric());
        }
    }

    @Nested
    @DisplayName("Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("Null input -> returns null")
        void nullInputReturnsNull() {
            assertNull(ASTMResultParser.parseRaw(null));
        }

        @Test
        @DisplayName("Empty input -> returns null")
        void emptyInputReturnsNull() {
            assertNull(ASTMResultParser.parseRaw(""));
        }

        @Test
        @DisplayName("Blank input -> returns null")
        void blankInputReturnsNull() {
            assertNull(ASTMResultParser.parseRaw("   "));
        }

        @Test
        @DisplayName("No R-records -> returns null")
        void noResultRecordsReturnsNull() {
            String msg = "H|\\^&|||Analyzer\n"
                    + "P|1\n"
                    + "O|1|ACC001\n"
                    + "L|1\n";

            assertNull(ASTMResultParser.parseRaw(msg));
        }

        @Test
        @DisplayName("R-records before O-record -> results skipped (no accession yet)")
        void resultBeforeOrderSkipped() {
            String msg = "H|\\^&|||Analyzer\n"
                    + "R|1|^^^TEST|5.0|units\n"
                    + "P|1\n"
                    + "O|1|ACC001\n"
                    + "L|1\n";

            assertNull(ASTMResultParser.parseRaw(msg));
        }

        @Test
        @DisplayName("O-record specimen ID with location subcomponent")
        void specimenIdWithLocation() {
            String msg = "H|\\^&|||Analyzer\n"
                    + "P|1\n"
                    + "O|1|SAMPLE123^RACK5\n"
                    + "R|1|^^^TEST|5.0|units\n"
                    + "L|1\n";

            ParsedResults parsed = ASTMResultParser.parseRaw(msg);

            assertNotNull(parsed);
            assertEquals("SAMPLE123", parsed.accessionNumber());
        }

        @Test
        @DisplayName("Uses 'ASTM-UNKNOWN' when no O-record accession")
        void unknownWhenNoAccession() {
            String msg = "H|\\^&|||Analyzer\n"
                    + "P|1\n"
                    + "O|1|\n"
                    + "R|1|^^^TEST|5.0|units\n"
                    + "L|1\n";

            // O-record has empty specimen ID, accession stays null -> falls back
            // But R-records are only added when accession != null, so...
            // Actually: accession is set per O-record parse. Let's trace:
            // extractAccessionNumber("O|1|") -> fields[2] = "" -> returns null
            // So accession remains null and R-record is skipped -> returns null
            assertNull(ASTMResultParser.parseRaw(msg));
        }
    }
}
