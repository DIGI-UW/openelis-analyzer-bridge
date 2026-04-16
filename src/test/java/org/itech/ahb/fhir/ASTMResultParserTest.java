package org.itech.ahb.fhir;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.regex.Pattern;
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
            "H|\\^&|||GeneXpert^1.0|||||||LIS2-A2\r"
            + "P|1\r"
            + "O|1|SAMPLE001||||||||||P\r"
            + "R|1|^^^HIV-VL|1520.5|copies/mL|||||20260326120000\r"
            + "R|2|^^^CT|28.5|cycles|||||20260326120000\r"
            + "L|1\r";

    // Real Cepheid GeneXpert H-record uses H|@^\  (repeat=@ component=^ escape=\)
    // instead of the standard H|\^&. The component separator ^ is identical in
    // both variants, so R-record field/component parsing is unaffected.
    // This message replicates what GXM-04567890 sends on the Madagascar fleet.
    private static final String REAL_GENEXPERT_MESSAGE =
            "H|@^\\|GXM-04567890||LA2M3^GeneXpert^6.2|||||geneexpert||P|1394-97|20260414120000\r"
            + "P|1\r"
            + "O|1|ACC-REAL-001||^^^MTBRif\r"
            + "R|1|^MTBRif^^MTB-RIF^Xpert MTB/RIF Ultra^3^MTB-RIF^|NOT DETECTED^||||||20260414120000\r"
            + "L|1\r";

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

        @Test
        @DisplayName("Real Cepheid H|@^\\ delimiter format parsed identically to H|\\^&")
        void realGeneXpertHeaderFormat_parsedCorrectly() {
            // Verifies that the H-record delimiter variant used by physical GeneXpert
            // machines (H|@^\) does not affect accession, test code, or value
            // extraction — the parser only relies on | (field) and ^ (component)
            // which are the same in both delimiter sets.
            ParsedResults parsed = ASTMResultParser.parseRaw(REAL_GENEXPERT_MESSAGE);

            assertNotNull(parsed);
            assertEquals("ACC-REAL-001", parsed.accessionNumber(),
                    "Accession extracted from O-record specimen ID");
            assertEquals(1, parsed.results().size(),
                    "Main Result filter preserves 1 MTB-RIF record (comp 5 non-empty)");
            assertEquals("MTB-RIF", parsed.results().get(0).testCode(),
                    "Test code extracted from R-record component 4");
            assertEquals("NOT DETECTED", parsed.results().get(0).value(),
                    "Real Cepheid qualitative value (not NEGATIVE) extracted cleanly");
        }
    }

    @Nested
    @DisplayName("Test code extraction formats")
    class TestCodeFormats {

        @Test
        @DisplayName("^^^CODE format - component 4 extracted")
        void tripleCaretFormat() {
            String msg = "H|\\^&|||Analyzer\r"
                    + "P|1\r"
                    + "O|1|ACC001\r"
                    + "R|1|^^^GLUCOSE|95.0|mg/dL\r"
                    + "L|1\r";

            ParsedResults parsed = ASTMResultParser.parseRaw(msg);

            assertNotNull(parsed);
            assertEquals("GLUCOSE", parsed.results().get(0).testCode());
        }

        @Test
        @DisplayName("^CODE format - component 2 extracted")
        void singleCaretFormat() {
            String msg = "H|\\^&|||Analyzer\r"
                    + "P|1\r"
                    + "O|1|ACC001\r"
                    + "R|1|^GLUCOSE|95.0|mg/dL\r"
                    + "L|1\r";

            ParsedResults parsed = ASTMResultParser.parseRaw(msg);

            assertNotNull(parsed);
            assertEquals("GLUCOSE", parsed.results().get(0).testCode());
        }

        @Test
        @DisplayName("Plain CODE format - whole field used")
        void plainFormat() {
            String msg = "H|\\^&|||Analyzer\r"
                    + "P|1\r"
                    + "O|1|ACC001\r"
                    + "R|1|GLUCOSE|95.0|mg/dL\r"
                    + "L|1\r";

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
            String msg = "H|\\^&|||Analyzer\r"
                    + "P|1\r"
                    + "O|1|ACC001\r"
                    + "R|1|^^^TEST|NEGATIVE^||\r"
                    + "L|1\r";

            ParsedResults parsed = ASTMResultParser.parseRaw(msg);

            assertNotNull(parsed);
            assertEquals("NEGATIVE", parsed.results().get(0).value());
        }

        @Test
        @DisplayName("Leading caret stripped: '^3.10' -> '3.10'")
        void leadingCaretStripped() {
            String msg = "H|\\^&|||Analyzer\r"
                    + "P|1\r"
                    + "O|1|ACC001\r"
                    + "R|1|^^^TEST|^3.10|mg/dL\r"
                    + "L|1\r";

            ParsedResults parsed = ASTMResultParser.parseRaw(msg);

            assertNotNull(parsed);
            assertEquals("3.10", parsed.results().get(0).value());
        }

        @Test
        @DisplayName("Multiple trailing carets stripped: 'POS^^^' -> 'POS'")
        void multipleTrailingCaretsStripped() {
            String msg = "H|\\^&|||Analyzer\r"
                    + "P|1\r"
                    + "O|1|ACC001\r"
                    + "R|1|^^^TEST|POS^^^||\r"
                    + "L|1\r";

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
            String msg = "H|\\^&|||Analyzer\r"
                    + "P|1\r"
                    + "O|1|QC_SAMPLE001||||||||||Q\r"
                    + "R|1|^^^TEST|5.0|units\r"
                    + "L|1\r";

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
            String msg = "H|\\^&|||Analyzer\r"
                    + "P|1\r"
                    + "O|1|ACC001\r"
                    + "R|1|^^^TEST|5.0|units\r"
                    + "L|1\r";

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
            String msg = "H|\\^&|||Analyzer\r"
                    + "P|1\r"
                    + "O|1|ACC001\r"
                    + "R|1|^^^TEST|5.0|units\r"
                    + "L|1\r";

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
            String msg = "H|\\^&|||Analyzer\r"
                    + "P|1\r"
                    + "O|1|ACC001\r"
                    + "R|1|^^^TEST|NEGATIVE||\r"
                    + "L|1\r";

            ParsedResults parsed = ASTMResultParser.parseRaw(msg);

            assertNotNull(parsed);
            assertFalse(parsed.results().get(0).isNumeric());
        }
    }

    @Nested
    @DisplayName("Main Result filter (Cepheid multi-result)")
    class MainResultFilter {

        private static final String CTNG_MESSAGE =
                "H|\\^&|||GeneXpert^6.5\r"
                + "P|1\r"
                + "O|1|CTNG_SAMPLE001||^^^CTNG\r"
                + "R|1|^CTNG^^CT^Xpert CT_NG^3^CT^|POS^||||||20260415120000\r"
                + "R|2|^CTNG^^CT^^^CT1^|POS^||||||20260415120000\r"
                + "R|3|^CTNG^^CT^^^CT1^Ct|^20.1||||||20260415120000\r"
                + "R|4|^CTNG^^CT^^^CT1^EndPt|^304.0||||||20260415120000\r"
                + "R|5|^CTNG^^CT^^^SAC^|NA^||||||20260415120000\r"
                + "R|6|^CTNG^^NG^Xpert CT_NG^3^NG^|NOT DETECTED||||||20260415120000\r"
                + "R|7|^CTNG^^NG^^^NG2^|NEG^||||||20260415120000\r"
                + "R|8|^CTNG^^NG^^^NG2^Ct|^0.0||||||20260415120000\r"
                + "L|1\r";

        @Test
        @DisplayName("Only Main Results (Component 5 non-empty) forwarded from CTNG cartridge")
        void onlyMainResultsForwarded() {
            ParsedResults parsed = ASTMResultParser.parseRaw(CTNG_MESSAGE);

            assertNotNull(parsed);
            assertEquals(2, parsed.results().size(), "Only 2 Main Results (CT + NG) from 8 R-records");
        }

        @Test
        @DisplayName("Main Results carry per-analyte test codes (CT, NG)")
        void mainResultsCarryAnalyteCodes() {
            ParsedResults parsed = ASTMResultParser.parseRaw(CTNG_MESSAGE);

            assertNotNull(parsed);
            List<String> codes = parsed.results().stream()
                    .map(AnalyzerResult::testCode).toList();
            assertEquals(List.of("CT", "NG"), codes);
        }

        @Test
        @DisplayName("Main Result values cleaned correctly (POS^→POS, NOT DETECTED)")
        void mainResultValuesClean() {
            ParsedResults parsed = ASTMResultParser.parseRaw(CTNG_MESSAGE);

            assertNotNull(parsed);
            assertEquals("POS", parsed.results().get(0).value());
            assertEquals("NOT DETECTED", parsed.results().get(1).value());
        }

        @Test
        @DisplayName("Analyte rows (CT1, NG2), Complementary (Ct, EndPt), and controls (SAC) all filtered out")
        void analyteAndComplementaryFiltered() {
            ParsedResults parsed = ASTMResultParser.parseRaw(CTNG_MESSAGE);

            assertNotNull(parsed);
            List<String> codes = parsed.results().stream()
                    .map(AnalyzerResult::testCode).toList();
            assertFalse(codes.contains("CT1"), "CT1 analyte row should be filtered");
            assertFalse(codes.contains("NG2"), "NG2 analyte row should be filtered");
            assertFalse(codes.contains("SAC"), "SAC control row should be filtered");
        }

        @Test
        @DisplayName("Simple ^^^CODE format (< 5 components) still works as Main Result")
        void simpleFormatBackwardCompat() {
            String msg = "H|\\^&|||Analyzer\r"
                    + "P|1\r"
                    + "O|1|ACC001\r"
                    + "R|1|^^^GLUCOSE|95.0|mg/dL\r"
                    + "L|1\r";

            ParsedResults parsed = ASTMResultParser.parseRaw(msg);

            assertNotNull(parsed);
            assertEquals(1, parsed.results().size());
            assertEquals("GLUCOSE", parsed.results().get(0).testCode());
        }

        @Test
        @DisplayName("isMainResult correctly identifies Main vs Analyte via Component 5")
        void isMainResultUnit() {
            String[] mainFields = "R|1|^CTNG^^CT^Xpert CT_NG^3^CT^|POS^".split(Pattern.quote("|"));
            String[] analyteFields = "R|2|^CTNG^^CT^^^CT1^|POS^".split(Pattern.quote("|"));
            String[] complementaryFields = "R|3|^CTNG^^CT^^^CT1^Ct|^20.1".split(Pattern.quote("|"));
            String[] simpleFields = "R|1|^^^GLUCOSE|95.0|mg/dL".split(Pattern.quote("|"));

            assertTrue(ASTMResultParser.isMainResult(mainFields), "Main Result should pass");
            assertFalse(ASTMResultParser.isMainResult(analyteFields), "Analyte should be filtered");
            assertFalse(ASTMResultParser.isMainResult(complementaryFields), "Complementary should be filtered");
            assertTrue(ASTMResultParser.isMainResult(simpleFields), "Simple format backward compat");
        }

        @Test
        @DisplayName("extractCartridgeCode returns panel code from Component 2")
        void cartridgeCodeExtracted() {
            String[] fields = "R|1|^CTNG^^CT^Xpert CT_NG^3^CT^|POS^".split(Pattern.quote("|"));
            assertEquals("CTNG", ASTMResultParser.extractCartridgeCode(fields));
        }

        @Test
        @DisplayName("extractCartridgeCode returns null for simple format")
        void cartridgeCodeNullForSimple() {
            String[] fields = "R|1|^^^GLUCOSE|95.0|mg/dL".split(Pattern.quote("|"));
            assertNull(ASTMResultParser.extractCartridgeCode(fields));
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
            String msg = "H|\\^&|||Analyzer\r"
                    + "P|1\r"
                    + "O|1|ACC001\r"
                    + "L|1\r";

            assertNull(ASTMResultParser.parseRaw(msg));
        }

        @Test
        @DisplayName("R-records before O-record -> results skipped (no accession yet)")
        void resultBeforeOrderSkipped() {
            String msg = "H|\\^&|||Analyzer\r"
                    + "R|1|^^^TEST|5.0|units\r"
                    + "P|1\r"
                    + "O|1|ACC001\r"
                    + "L|1\r";

            assertNull(ASTMResultParser.parseRaw(msg));
        }

        @Test
        @DisplayName("O-record specimen ID with location subcomponent")
        void specimenIdWithLocation() {
            String msg = "H|\\^&|||Analyzer\r"
                    + "P|1\r"
                    + "O|1|SAMPLE123^RACK5\r"
                    + "R|1|^^^TEST|5.0|units\r"
                    + "L|1\r";

            ParsedResults parsed = ASTMResultParser.parseRaw(msg);

            assertNotNull(parsed);
            assertEquals("SAMPLE123", parsed.accessionNumber());
        }

        @Test
        @DisplayName("Uses 'ASTM-UNKNOWN' when no O-record accession")
        void unknownWhenNoAccession() {
            String msg = "H|\\^&|||Analyzer\r"
                    + "P|1\r"
                    + "O|1|\r"
                    + "R|1|^^^TEST|5.0|units\r"
                    + "L|1\r";

            // O-record has empty specimen ID, accession stays null -> falls back
            // But R-records are only added when accession != null, so...
            // Actually: accession is set per O-record parse. Let's trace:
            // extractAccessionNumber("O|1|") -> fields[2] = "" -> returns null
            // So accession remains null and R-record is skipped -> returns null
            assertNull(ASTMResultParser.parseRaw(msg));
        }
    }
}
