package org.itech.ahb.serial;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

import org.itech.ahb.model.Protocol;
import org.itech.ahb.model.Transport;
import org.itech.ahb.normalizer.MessageEnvelope;
import org.itech.ahb.normalizer.MessageNormalizer;
import org.itech.ahb.serial.SerialMessageHandler.HandleResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for SerialMessageHandler class.
 * <p>
 * Tests protocol detection, envelope creation, and delegation to MessageNormalizer.
 * After M7 refactoring, this handler no longer does direct HTTP forwarding —
 * it creates a MessageEnvelope and delegates to the normalizer.
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class SerialMessageHandlerTest {

    @Mock
    private MessageNormalizer mockNormalizer;

    private SerialMessageHandler handler;

    @BeforeEach
    void setUp() {
        handler = new SerialMessageHandler(mockNormalizer);
        // Lenient: ErrorHandlingTests override or never call process
        lenient().when(mockNormalizer.process(any(MessageEnvelope.class))).thenReturn(true);
    }

    @Nested
    @DisplayName("Protocol Detection Tests")
    class ProtocolDetectionTests {

        @Test
        @DisplayName("Should detect ASTM protocol and create correct envelope")
        void shouldDetectASTMProtocol() {
            String astmMessage = "H|\\^&|||ANALYZER|||||||P|1|20260205120000\r" +
                                "P|1||12345\r" +
                                "L|1|N";

            HandleResult result = handler.handleMessage(astmMessage, "/dev/ttyUSB0", null);

            assertTrue(result.success());
            verify(mockNormalizer).process(argThat(envelope ->
                envelope.getProtocol() == Protocol.ASTM &&
                envelope.getTransport() == Transport.SERIAL &&
                "/dev/ttyUSB0".equals(envelope.getSourceId()) &&
                astmMessage.equals(envelope.getRawMessage()) &&
                envelope.getAnalyzerId() == null
            ));
        }

        @Test
        @DisplayName("Should detect HL7 protocol and create correct envelope")
        void shouldDetectHL7Protocol() {
            String hl7Message = "MSH|^~\\&|TEST|LAB|OPENELIS|LAB|20260205120000||ORU^R01|MSG001|P|2.5.1\r" +
                               "PID|1||12345||DOE^JOHN\r" +
                               "OBX|1|NM|WBC||7.5|10^3/uL";

            HandleResult result = handler.handleMessage(hl7Message, "/dev/ttyUSB0", null);

            assertTrue(result.success());
            verify(mockNormalizer).process(argThat(envelope ->
                envelope.getProtocol() == Protocol.HL7 &&
                envelope.getTransport() == Transport.SERIAL &&
                "/dev/ttyUSB0".equals(envelope.getSourceId()) &&
                hl7Message.equals(envelope.getRawMessage())
            ));
        }

        @Test
        @DisplayName("Should detect CSV protocol and create correct envelope")
        void shouldDetectCSVProtocol() {
            String csvMessage = "SampleID,TestCode,Result,Unit,Flag\r\n" +
                               "12345,WBC,7.5,10^3/uL,N\r\n" +
                               "12345,RBC,4.8,10^6/uL,N";

            HandleResult result = handler.handleMessage(csvMessage, "/dev/ttyUSB0", null);

            assertTrue(result.success());
            verify(mockNormalizer).process(argThat(envelope ->
                envelope.getProtocol() == Protocol.CSV &&
                envelope.getTransport() == Transport.SERIAL &&
                csvMessage.equals(envelope.getRawMessage())
            ));
        }

        @Test
        @DisplayName("Should detect unknown protocol and create envelope")
        void shouldDetectUnknownProtocol() {
            String unknownMessage = "This is some unknown format";

            HandleResult result = handler.handleMessage(unknownMessage, "/dev/ttyUSB0", null);

            assertTrue(result.success());
            verify(mockNormalizer).process(argThat(envelope ->
                envelope.getProtocol() == Protocol.UNKNOWN &&
                envelope.getTransport() == Transport.SERIAL
            ));
        }
    }

    @Nested
    @DisplayName("Envelope Metadata Tests")
    class EnvelopeMetadataTests {

        @Test
        @DisplayName("Should include source ID in envelope")
        void shouldIncludeSourceIdInEnvelope() {
            String message = "H|\\^&|||TEST";

            handler.handleMessage(message, "/dev/ttyUSB0", null);

            verify(mockNormalizer).process(argThat(envelope ->
                "/dev/ttyUSB0".equals(envelope.getSourceId())
            ));
        }

        @Test
        @DisplayName("Should include SERIAL transport in envelope")
        void shouldIncludeTransportInEnvelope() {
            String message = "H|\\^&|||TEST";

            handler.handleMessage(message, "/dev/ttyUSB0", null);

            verify(mockNormalizer).process(argThat(envelope ->
                envelope.getTransport() == Transport.SERIAL
            ));
        }

        @Test
        @DisplayName("Should include protocol analyzer hint in envelope when provided")
        void shouldIncludeProtocolAnalyzerHintInEnvelope() {
            String message = "H|\\^&|||TEST";

            handler.handleMessage(message, "/dev/ttyUSB0", "ANALYZER-001");

            verify(mockNormalizer).process(argThat(envelope ->
                "ANALYZER-001".equals(envelope.getProtocolAnalyzerHint()) &&
                envelope.getAnalyzerId() == null
            ));
        }

        @Test
        @DisplayName("Should have null protocol analyzer hint when not provided")
        void shouldHaveNullProtocolAnalyzerHintWhenNotProvided() {
            String message = "H|\\^&|||TEST";

            handler.handleMessage(message, "/dev/ttyUSB0", null);

            verify(mockNormalizer).process(argThat(envelope ->
                envelope.getProtocolAnalyzerHint() == null &&
                envelope.getAnalyzerId() == null
            ));
        }
    }

    @Nested
    @DisplayName("Error Handling Tests")
    class ErrorHandlingTests {

        @Test
        @DisplayName("Should handle empty message")
        void shouldHandleEmptyMessage() {
            HandleResult result = handler.handleMessage("", "/dev/ttyUSB0", null);

            assertFalse(result.success());
            assertEquals("Empty message", result.message());
            verify(mockNormalizer, never()).process(any());
        }

        @Test
        @DisplayName("Should handle null message")
        void shouldHandleNullMessage() {
            HandleResult result = handler.handleMessage(null, "/dev/ttyUSB0", null);

            assertFalse(result.success());
            assertEquals("Empty message", result.message());
            verify(mockNormalizer, never()).process(any());
        }

        @Test
        @DisplayName("Should return failure when normalizer returns false")
        void shouldReturnFailureWhenNormalizerReturnsFalse() {
            when(mockNormalizer.process(any(MessageEnvelope.class))).thenReturn(false);

            String message = "H|\\^&|||TEST";
            HandleResult result = handler.handleMessage(message, "/dev/ttyUSB0", null);

            assertFalse(result.success());
            assertEquals("Routing failed", result.message());
        }

        @Test
        @DisplayName("Should return success when normalizer returns true")
        void shouldReturnSuccessWhenNormalizerReturnsTrue() {
            when(mockNormalizer.process(any(MessageEnvelope.class))).thenReturn(true);

            String message = "H|\\^&|||TEST";
            HandleResult result = handler.handleMessage(message, "/dev/ttyUSB0", null);

            assertTrue(result.success());
            assertEquals("Routed via normalizer", result.message());
        }
    }

    @Nested
    @DisplayName("Complex Message Tests")
    class ComplexMessageTests {

        @Test
        @DisplayName("Should handle complete ASTM result message")
        void shouldHandleCompleteASTMResultMessage() {
            String astmMessage =
                "H|\\^&|||MINDRAY^BC-5380^12345|||||||P|1|20260205120000\r" +
                "P|1||PAT001||DOE^JOHN||19800101|M\r" +
                "O|1|SAM001||^^^CBC|R||20260205120000\r" +
                "R|1|^^^WBC|7.5|10^3/uL|4.0-11.0|N||F|||20260205120000\r" +
                "R|2|^^^RBC|4.8|10^6/uL|4.5-5.5|N||F|||20260205120000\r" +
                "R|3|^^^HGB|14.2|g/dL|12.0-16.0|N||F|||20260205120000\r" +
                "R|4|^^^HCT|42.5|%|37.0-47.0|N||F|||20260205120000\r" +
                "R|5|^^^PLT|250|10^3/uL|150-400|N||F|||20260205120000\r" +
                "L|1|N";

            HandleResult result = handler.handleMessage(astmMessage, "/dev/ttyUSB0", "MINDRAY-001");

            assertTrue(result.success());
            verify(mockNormalizer).process(argThat(envelope ->
                astmMessage.equals(envelope.getRawMessage()) &&
                "MINDRAY-001".equals(envelope.getProtocolAnalyzerHint())
            ));
        }

        @Test
        @DisplayName("Should handle complete HL7 ORU message")
        void shouldHandleCompleteHL7ORUMessage() {
            String hl7Message =
                "MSH|^~\\&|ANALYZER|LAB|OPENELIS|LAB|20260205120000||ORU^R01|MSG001|P|2.5.1\r" +
                "PID|1||PAT001||DOE^JOHN||19800101|M|||123 MAIN ST^^CITY^ST^12345\r" +
                "PV1|1|O|LAB|||||||||||||||V001\r" +
                "ORC|RE|ORD001|SAM001|||||||20260205120000|||DR^SMITH\r" +
                "OBR|1|ORD001|SAM001|CBC^COMPLETE BLOOD COUNT|||20260205110000\r" +
                "OBX|1|NM|WBC^WHITE BLOOD CELL||7.5|10^3/uL|4.0-11.0|N|||F\r" +
                "OBX|2|NM|RBC^RED BLOOD CELL||4.8|10^6/uL|4.5-5.5|N|||F\r" +
                "OBX|3|NM|HGB^HEMOGLOBIN||14.2|g/dL|12.0-16.0|N|||F";

            HandleResult result = handler.handleMessage(hl7Message, "/dev/ttyUSB0", null);

            assertTrue(result.success());
            verify(mockNormalizer).process(argThat(envelope ->
                envelope.getProtocol() == Protocol.HL7 &&
                hl7Message.equals(envelope.getRawMessage())
            ));
        }

        @Test
        @DisplayName("Should handle message with special characters")
        void shouldHandleMessageWithSpecialCharacters() {
            String message = "H|\\^&|||TEST^with\\special|characters~here\r" +
                            "P|1||ID&with^special|chars\r" +
                            "L|1|N";

            HandleResult result = handler.handleMessage(message, "/dev/ttyUSB0", null);

            assertTrue(result.success());
            verify(mockNormalizer).process(argThat(envelope ->
                message.equals(envelope.getRawMessage())
            ));
        }

        @Test
        @DisplayName("Should handle large message")
        void shouldHandleLargeMessage() {
            // Build a large message with many result segments
            StringBuilder sb = new StringBuilder();
            sb.append("H|\\^&|||ANALYZER|||||||P|1|20260205120000\r");
            sb.append("P|1||PATIENT001\r");

            for (int i = 1; i <= 100; i++) {
                sb.append(String.format("R|%d|^^^TEST%d|%d.%d|units|1-100|N||F\r",
                    i, i, i * 10, i));
            }
            sb.append("L|1|N");

            String message = sb.toString();
            HandleResult result = handler.handleMessage(message, "/dev/ttyUSB0", null);

            assertTrue(result.success());
            verify(mockNormalizer).process(argThat(envelope ->
                message.equals(envelope.getRawMessage())
            ));
        }
    }
}
