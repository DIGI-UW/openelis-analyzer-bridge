package org.itech.ahb.util;

import org.itech.ahb.model.Protocol;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ProtocolDetector utility class.
 */
class ProtocolDetectorTest {

    @Test
    void testDetectASTM_withSTXCharacter() {
        String message = "\u0002H|\\^&|||...";
        assertEquals(Protocol.ASTM, ProtocolDetector.detect(message));
        assertTrue(ProtocolDetector.isASTM(message));
    }

    @Test
    void testDetectASTM_withHeaderMarker() {
        String message = "H|\\^&|||TEST|||...";
        assertEquals(Protocol.ASTM, ProtocolDetector.detect(message));
        assertTrue(ProtocolDetector.isASTM(message));
    }

    @Test
    void testDetectHL7_withMSHSegment() {
        String message = "MSH|^~\\&|TEST|||20260205120000||ORU^R01|MSG001|P|2.5.1";
        assertEquals(Protocol.HL7, ProtocolDetector.detect(message));
        assertTrue(ProtocolDetector.isHL7(message));
    }

    @Test
    void testDetectHL7_withWhitespace() {
        String message = "  \nMSH|^~\\&|TEST|||20260205120000||ORU^R01|MSG001|P|2.5.1";
        assertEquals(Protocol.HL7, ProtocolDetector.detect(message));
    }

    @Test
    void testDetectCSV_withMultipleColumns() {
        String message = "SampleID,TestCode,Result,Units,Timestamp\n" +
                        "12345,GLU,95,mg/dL,2026-02-05T12:00:00";
        assertEquals(Protocol.CSV, ProtocolDetector.detect(message));
        assertTrue(ProtocolDetector.isCSV(message));
    }

    @Test
    void testDetectCSV_minimumColumns() {
        // Requires at least 4 columns (>3)
        String message = "A,B,C,D";
        assertEquals(Protocol.CSV, ProtocolDetector.detect(message));
    }

    @Test
    void testDetectUnknown_withTooFewCommas() {
        // Only 3 columns - not enough for CSV
        String message = "A,B,C";
        assertEquals(Protocol.UNKNOWN, ProtocolDetector.detect(message));
    }

    @Test
    void testDetectUnknown_withNullMessage() {
        assertEquals(Protocol.UNKNOWN, ProtocolDetector.detect(null));
    }

    @Test
    void testDetectUnknown_withEmptyMessage() {
        assertEquals(Protocol.UNKNOWN, ProtocolDetector.detect(""));
    }

    @Test
    void testDetectUnknown_withWhitespaceOnly() {
        assertEquals(Protocol.UNKNOWN, ProtocolDetector.detect("   \n\t  "));
    }

    @Test
    void testDetectUnknown_withUnrecognizedFormat() {
        String message = "This is just plain text with no structure";
        assertEquals(Protocol.UNKNOWN, ProtocolDetector.detect(message));
    }

    @Test
    void testIsASTM_returnsFalseForHL7() {
        String message = "MSH|^~\\&|TEST|||20260205120000||ORU^R01|MSG001|P|2.5.1";
        assertFalse(ProtocolDetector.isASTM(message));
    }

    @Test
    void testIsHL7_returnsFalseForASTM() {
        String message = "H|\\^&|||TEST|||...";
        assertFalse(ProtocolDetector.isHL7(message));
    }

    @Test
    void testIsCSV_returnsFalseForASTM() {
        String message = "H|\\^&|||TEST|||...";
        assertFalse(ProtocolDetector.isCSV(message));
    }

    @Test
    void testComplexASTMMessage() {
        String message = "H|\\^&|||MINDRAY^BC-5380|||||||P|1|20260205120000\r" +
                        "P|1||12345||DOE^JOHN||19800101|M\r" +
                        "O|1|12345||^^^WBC\\^^^RBC|R||20260205120000\r" +
                        "R|1|^^^WBC|7.5|10^3/uL||N||F\r" +
                        "R|2|^^^RBC|4.8|10^6/uL||N||F\r" +
                        "L|1|N";
        assertEquals(Protocol.ASTM, ProtocolDetector.detect(message));
    }

    @Test
    void testComplexHL7Message() {
        String message = "MSH|^~\\&|MINDRAY^BC-5380|LAB|OpenELIS|OpenELIS|20260205120000||ORU^R01|MSG001|P|2.5.1\r" +
                        "PID|1||12345||DOE^JOHN||19800101|M\r" +
                        "OBR|1||12345|CBC^COMPLETE BLOOD COUNT\r" +
                        "OBX|1|NM|WBC^White Blood Cell Count||7.5|10^3/uL|4.0-11.0|N|||F\r" +
                        "OBX|2|NM|RBC^Red Blood Cell Count||4.8|10^6/uL|4.5-5.5|N|||F";
        assertEquals(Protocol.HL7, ProtocolDetector.detect(message));
    }

    @Test
    void testComplexCSVMessage() {
        String message = "Sample ID,Test Code,Result,Unit,Flag,Timestamp\r\n" +
                        "12345,WBC,7.5,10^3/uL,N,2026-02-05T12:00:00\r\n" +
                        "12345,RBC,4.8,10^6/uL,N,2026-02-05T12:00:00";
        assertEquals(Protocol.CSV, ProtocolDetector.detect(message));
    }
}
