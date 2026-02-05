package org.itech.ahb.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.itech.ahb.model.Protocol;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for AnalyzerInputController.
 * Tests HTTP input endpoint for ASTM, HL7, and CSV messages.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AnalyzerInputControllerTest {

    private AnalyzerInputController controller;

    @Mock
    private HttpServletRequest mockRequest;

    // Sample messages
    private static final String SAMPLE_ASTM_MESSAGE = "H|\\^&|||HOST^NAME|||||||LIS2-A2|20260205120000\r" +
            "P|1||||Doe^John||19850101|M\r" +
            "O|1|12345||^^^GLU\r" +
            "R|1|^^^GLU|95|mg/dL||N||F||20260205120000\r" +
            "L|1|N";

    private static final String SAMPLE_HL7_MESSAGE = "MSH|^~\\&|ANALYZER|LAB|OPENELIS|HOST|20260205120000||ORU^R01|123456|P|2.5.1\r" +
            "PID|1||12345||Doe^John||19850101|M\r" +
            "OBR|1|12345||GLU^Glucose\r" +
            "OBX|1|NM|GLU^Glucose||95|mg/dL||N||F";

    private static final String SAMPLE_CSV_MESSAGE = "SampleID,TestCode,Result,Units,Flags,DateTime\n" +
            "12345,GLU,95,mg/dL,N,20260205120000\n" +
            "12346,HGB,14.2,g/dL,N,20260205120100\n" +
            "12347,WBC,7.5,10^3/uL,,20260205120200";

    @BeforeEach
    void setUp() {
        controller = new AnalyzerInputController();
        lenient().when(mockRequest.getRemoteAddr()).thenReturn("127.0.0.1");
    }

    @Nested
    @DisplayName("Protocol Detection via Content-Type")
    class ContentTypeProtocolDetectionTests {

        @Test
        @DisplayName("Should detect HL7 from application/hl7-v2 Content-Type")
        void shouldDetectHL7FromContentType() {
            ResponseEntity<AnalyzerInputController.InputResponse> response =
                    controller.receiveAnalyzerMessage(SAMPLE_HL7_MESSAGE, "application/hl7-v2", null, mockRequest);

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertNotNull(response.getBody());
            assertTrue(response.getBody().success());
            assertEquals("HL7", response.getBody().protocol());
        }

        @Test
        @DisplayName("Should detect HL7 from x-application/hl7-v2 Content-Type")
        void shouldDetectHL7FromAltContentType() {
            ResponseEntity<AnalyzerInputController.InputResponse> response =
                    controller.receiveAnalyzerMessage(SAMPLE_HL7_MESSAGE, "x-application/hl7-v2", null, mockRequest);

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertNotNull(response.getBody());
            assertEquals("HL7", response.getBody().protocol());
        }

        @Test
        @DisplayName("Should detect CSV from text/csv Content-Type")
        void shouldDetectCSVFromContentType() {
            ResponseEntity<AnalyzerInputController.InputResponse> response =
                    controller.receiveAnalyzerMessage(SAMPLE_CSV_MESSAGE, "text/csv", null, mockRequest);

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertNotNull(response.getBody());
            assertEquals("CSV", response.getBody().protocol());
        }

        @Test
        @DisplayName("Should detect CSV from application/csv Content-Type")
        void shouldDetectCSVFromApplicationCsvContentType() {
            ResponseEntity<AnalyzerInputController.InputResponse> response =
                    controller.receiveAnalyzerMessage(SAMPLE_CSV_MESSAGE, "application/csv", null, mockRequest);

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertNotNull(response.getBody());
            assertEquals("CSV", response.getBody().protocol());
        }

        @Test
        @DisplayName("Should handle Content-Type with charset parameter")
        void shouldHandleContentTypeWithCharset() {
            ResponseEntity<AnalyzerInputController.InputResponse> response =
                    controller.receiveAnalyzerMessage(SAMPLE_HL7_MESSAGE, "application/hl7-v2; charset=utf-8", null, mockRequest);

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertNotNull(response.getBody());
            assertEquals("HL7", response.getBody().protocol());
        }

        @Test
        @DisplayName("Should detect ASTM from astm content type")
        void shouldDetectASTMFromContentType() {
            ResponseEntity<AnalyzerInputController.InputResponse> response =
                    controller.receiveAnalyzerMessage(SAMPLE_ASTM_MESSAGE, "application/x-astm", null, mockRequest);

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertNotNull(response.getBody());
            assertEquals("ASTM", response.getBody().protocol());
        }
    }

    @Nested
    @DisplayName("Protocol Auto-Detection from Message Content")
    class AutoDetectionTests {

        @Test
        @DisplayName("Should auto-detect ASTM from message content")
        void shouldAutoDetectASTM() {
            ResponseEntity<AnalyzerInputController.InputResponse> response =
                    controller.receiveAnalyzerMessage(SAMPLE_ASTM_MESSAGE, "text/plain", null, mockRequest);

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertNotNull(response.getBody());
            assertEquals("ASTM", response.getBody().protocol());
        }

        @Test
        @DisplayName("Should auto-detect HL7 from message content (text/plain)")
        void shouldAutoDetectHL7() {
            ResponseEntity<AnalyzerInputController.InputResponse> response =
                    controller.receiveAnalyzerMessage(SAMPLE_HL7_MESSAGE, "text/plain", null, mockRequest);

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertNotNull(response.getBody());
            assertEquals("HL7", response.getBody().protocol());
        }

        @Test
        @DisplayName("Should auto-detect CSV from message content (text/plain)")
        void shouldAutoDetectCSV() {
            ResponseEntity<AnalyzerInputController.InputResponse> response =
                    controller.receiveAnalyzerMessage(SAMPLE_CSV_MESSAGE, "text/plain", null, mockRequest);

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertNotNull(response.getBody());
            assertEquals("CSV", response.getBody().protocol());
        }

        @Test
        @DisplayName("Should auto-detect ASTM starting with STX byte")
        void shouldAutoDetectASTMWithSTX() {
            String astmWithSTX = "\u0002" + SAMPLE_ASTM_MESSAGE;
            ResponseEntity<AnalyzerInputController.InputResponse> response =
                    controller.receiveAnalyzerMessage(astmWithSTX, "text/plain", null, mockRequest);

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertNotNull(response.getBody());
            assertEquals("ASTM", response.getBody().protocol());
        }

        @Test
        @DisplayName("Should auto-detect when Content-Type is null")
        void shouldAutoDetectWithNullContentType() {
            ResponseEntity<AnalyzerInputController.InputResponse> response =
                    controller.receiveAnalyzerMessage(SAMPLE_HL7_MESSAGE, null, null, mockRequest);

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertNotNull(response.getBody());
            assertEquals("HL7", response.getBody().protocol());
        }
    }

    @Nested
    @DisplayName("Source IP Extraction")
    class SourceIPExtractionTests {

        @Test
        @DisplayName("Should extract source IP from X-Forwarded-For header")
        void shouldExtractFromXForwardedFor() {
            ResponseEntity<AnalyzerInputController.InputResponse> response =
                    controller.receiveAnalyzerMessage(SAMPLE_ASTM_MESSAGE, "text/plain", "192.168.1.100", mockRequest);

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertNotNull(response.getBody());
            assertEquals("192.168.1.100", response.getBody().sourceIp());
        }

        @Test
        @DisplayName("Should extract first IP from X-Forwarded-For chain")
        void shouldExtractFirstIPFromChain() {
            ResponseEntity<AnalyzerInputController.InputResponse> response =
                    controller.receiveAnalyzerMessage(SAMPLE_ASTM_MESSAGE, "text/plain",
                            "192.168.1.100, 10.0.0.1, 172.16.0.1", mockRequest);

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertNotNull(response.getBody());
            assertEquals("192.168.1.100", response.getBody().sourceIp());
        }

        @Test
        @DisplayName("Should extract source IP from X-Real-IP when X-Forwarded-For is missing")
        void shouldExtractFromXRealIP() {
            when(mockRequest.getHeader("X-Real-IP")).thenReturn("10.20.30.40");

            ResponseEntity<AnalyzerInputController.InputResponse> response =
                    controller.receiveAnalyzerMessage(SAMPLE_ASTM_MESSAGE, "text/plain", null, mockRequest);

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertNotNull(response.getBody());
            assertEquals("10.20.30.40", response.getBody().sourceIp());
        }

        @Test
        @DisplayName("Should prioritize X-Forwarded-For over X-Real-IP")
        void shouldPrioritizeXForwardedFor() {
            when(mockRequest.getHeader("X-Real-IP")).thenReturn("10.20.30.40");

            ResponseEntity<AnalyzerInputController.InputResponse> response =
                    controller.receiveAnalyzerMessage(SAMPLE_ASTM_MESSAGE, "text/plain",
                            "192.168.1.100", mockRequest);

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertNotNull(response.getBody());
            assertEquals("192.168.1.100", response.getBody().sourceIp());
        }

        @Test
        @DisplayName("Should fall back to remote address when headers are missing")
        void shouldFallBackToRemoteAddress() {
            ResponseEntity<AnalyzerInputController.InputResponse> response =
                    controller.receiveAnalyzerMessage(SAMPLE_ASTM_MESSAGE, "text/plain", null, mockRequest);

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertNotNull(response.getBody());
            assertEquals("127.0.0.1", response.getBody().sourceIp());
        }

        @Test
        @DisplayName("Should return 'unknown' when no IP source available")
        void shouldReturnUnknownWhenNoIPAvailable() {
            when(mockRequest.getRemoteAddr()).thenReturn(null);

            ResponseEntity<AnalyzerInputController.InputResponse> response =
                    controller.receiveAnalyzerMessage(SAMPLE_ASTM_MESSAGE, "text/plain", null, mockRequest);

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertNotNull(response.getBody());
            assertEquals("unknown", response.getBody().sourceIp());
        }
    }

    @Nested
    @DisplayName("Error Handling")
    class ErrorHandlingTests {

        @Test
        @DisplayName("Should return 400 for null request body")
        void shouldRejectNullBody() {
            ResponseEntity<AnalyzerInputController.InputResponse> response =
                    controller.receiveAnalyzerMessage(null, "text/plain", null, mockRequest);

            assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
            assertNotNull(response.getBody());
            assertFalse(response.getBody().success());
            assertEquals("Request body is required", response.getBody().message());
        }

        @Test
        @DisplayName("Should return 400 for empty request body")
        void shouldRejectEmptyBody() {
            ResponseEntity<AnalyzerInputController.InputResponse> response =
                    controller.receiveAnalyzerMessage("", "text/plain", null, mockRequest);

            assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
            assertNotNull(response.getBody());
            assertFalse(response.getBody().success());
            assertEquals("Request body is required", response.getBody().message());
        }

        @Test
        @DisplayName("Should return 400 for whitespace-only request body")
        void shouldRejectWhitespaceBody() {
            ResponseEntity<AnalyzerInputController.InputResponse> response =
                    controller.receiveAnalyzerMessage("   \n\t  ", "text/plain", null, mockRequest);

            assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
            assertNotNull(response.getBody());
            assertFalse(response.getBody().success());
            assertEquals("Request body is required", response.getBody().message());
        }

        @Test
        @DisplayName("Should return 422 for unrecognized protocol")
        void shouldRejectUnknownProtocol() {
            String unknownMessage = "This is not a valid ASTM, HL7, or CSV message";
            ResponseEntity<AnalyzerInputController.InputResponse> response =
                    controller.receiveAnalyzerMessage(unknownMessage, "text/plain", null, mockRequest);

            assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, response.getStatusCode());
            assertNotNull(response.getBody());
            assertFalse(response.getBody().success());
            assertEquals("Unable to detect message protocol", response.getBody().message());
        }
    }

    @Nested
    @DisplayName("Response Structure")
    class ResponseStructureTests {

        @Test
        @DisplayName("Should include receivedAt timestamp in response")
        void shouldIncludeTimestamp() {
            ResponseEntity<AnalyzerInputController.InputResponse> response =
                    controller.receiveAnalyzerMessage(SAMPLE_ASTM_MESSAGE, "text/plain", null, mockRequest);

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertNotNull(response.getBody());
            assertNotNull(response.getBody().receivedAt());
            assertFalse(response.getBody().receivedAt().isEmpty());
        }

        @Test
        @DisplayName("Should return complete success response")
        void shouldReturnCompleteSuccessResponse() {
            ResponseEntity<AnalyzerInputController.InputResponse> response =
                    controller.receiveAnalyzerMessage(SAMPLE_HL7_MESSAGE, "application/hl7-v2",
                            "192.168.1.50", mockRequest);

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertNotNull(response.getBody());
            assertTrue(response.getBody().success());
            assertEquals("Message received successfully", response.getBody().message());
            assertEquals("192.168.1.50", response.getBody().sourceIp());
            assertEquals("HL7", response.getBody().protocol());
            assertNotNull(response.getBody().receivedAt());
        }
    }

    @Nested
    @DisplayName("Helper Method Unit Tests")
    class HelperMethodTests {

        @Test
        @DisplayName("detectProtocol should return HL7 for hl7 content types")
        void detectProtocolHL7ContentType() {
            assertEquals(Protocol.HL7, controller.detectProtocol("application/hl7-v2", ""));
            assertEquals(Protocol.HL7, controller.detectProtocol("x-application/hl7-v2", ""));
            assertEquals(Protocol.HL7, controller.detectProtocol("text/hl7", ""));
        }

        @Test
        @DisplayName("detectProtocol should return CSV for csv content types")
        void detectProtocolCSVContentType() {
            assertEquals(Protocol.CSV, controller.detectProtocol("text/csv", ""));
            assertEquals(Protocol.CSV, controller.detectProtocol("application/csv", ""));
        }

        @Test
        @DisplayName("detectProtocol should return ASTM for astm content types")
        void detectProtocolASTMContentType() {
            assertEquals(Protocol.ASTM, controller.detectProtocol("application/x-astm", ""));
            assertEquals(Protocol.ASTM, controller.detectProtocol("text/astm", ""));
        }

        @Test
        @DisplayName("detectProtocol should auto-detect from content when content type is text/plain")
        void detectProtocolAutoDetect() {
            assertEquals(Protocol.ASTM, controller.detectProtocol("text/plain", SAMPLE_ASTM_MESSAGE));
            assertEquals(Protocol.HL7, controller.detectProtocol("text/plain", SAMPLE_HL7_MESSAGE));
            assertEquals(Protocol.CSV, controller.detectProtocol("text/plain", SAMPLE_CSV_MESSAGE));
        }

        @Test
        @DisplayName("detectProtocol should auto-detect when content type is null")
        void detectProtocolNullContentType() {
            assertEquals(Protocol.ASTM, controller.detectProtocol(null, SAMPLE_ASTM_MESSAGE));
            assertEquals(Protocol.HL7, controller.detectProtocol(null, SAMPLE_HL7_MESSAGE));
        }

        @Test
        @DisplayName("extractSourceIp should handle various IP formats")
        void extractSourceIpVariousFormats() {
            // IPv4 with multiple proxies
            assertEquals("192.168.1.1",
                    controller.extractSourceIp("192.168.1.1, 10.0.0.1", mockRequest));

            // IPv6
            assertEquals("::1",
                    controller.extractSourceIp("::1", mockRequest));

            // Trimmed value
            assertEquals("192.168.1.1",
                    controller.extractSourceIp("  192.168.1.1  ", mockRequest));
        }

        @Test
        @DisplayName("extractSourceIp should handle empty X-Forwarded-For")
        void extractSourceIpEmptyXForwardedFor() {
            assertEquals("127.0.0.1",
                    controller.extractSourceIp("", mockRequest));
            assertEquals("127.0.0.1",
                    controller.extractSourceIp("   ", mockRequest));
        }
    }
}
