package org.itech.ahb.normalizer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

import org.itech.ahb.lib.astm.concept.ASTMMessage;
import org.itech.ahb.lib.astm.concept.DefaultASTMMessage;
import org.itech.ahb.lib.astm.handling.ASTMHandlerResponse;
import org.itech.ahb.lib.common.handling.HandleStatus;
import org.itech.ahb.model.Protocol;
import org.itech.ahb.model.Transport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for ASTMBridgeAdapter.
 * <p>
 * Tests the ASTM bridge adapter that implements ASTMHandler and delegates
 * to MessageNormalizer for routing to OpenELIS.
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class ASTMBridgeAdapterTest {

    @Mock
    private MessageNormalizer mockNormalizer;

    @Mock
    private ASTMMessage mockAstmMessage;

    private ASTMBridgeAdapter adapter;

    private static final String SAMPLE_ASTM_MESSAGE = "H|\\^&|||HOST^NAME|||||||LIS2-A2|20260205120000\r" +
            "P|1||||Doe^John||19850101|M\r" +
            "O|1|12345||^^^GLU\r" +
            "R|1|^^^GLU|95|mg/dL||N||F||20260205120000\r" +
            "L|1|N";

    @BeforeEach
    void setUp() {
        adapter = new ASTMBridgeAdapter(mockNormalizer);
        // Lenient: some tests override or do not call process
        lenient().when(mockNormalizer.process(any(MessageEnvelope.class))).thenReturn(true);
        lenient().when(mockAstmMessage.getMessage()).thenReturn(SAMPLE_ASTM_MESSAGE);
    }

    @Nested
    @DisplayName("Handler Identification Tests")
    class HandlerIdentificationTests {

        @Test
        @DisplayName("getName should return 'ASTM Bridge Adapter'")
        void getNameShouldReturnCorrectName() {
            String name = adapter.getName();
            assertEquals("ASTM Bridge Adapter", name);
        }

        @Test
        @DisplayName("matches should return true for DefaultASTMMessage")
        void matchesShouldReturnTrueForDefaultASTMMessage() {
            DefaultASTMMessage message = new DefaultASTMMessage(SAMPLE_ASTM_MESSAGE);

            boolean matches = adapter.matches(message);

            assertTrue(matches);
        }

        @Test
        @DisplayName("matches should return false for non-DefaultASTMMessage")
        void matchesShouldReturnFalseForNonDefaultASTMMessage() {
            // Mock a different ASTMMessage implementation
            ASTMMessage nonDefaultMessage = mock(ASTMMessage.class);

            boolean matches = adapter.matches(nonDefaultMessage);

            assertFalse(matches);
        }
    }

    @Nested
    @DisplayName("Message Handling Tests")
    class MessageHandlingTests {

        @Test
        @DisplayName("Should create envelope with Protocol.ASTM and Transport.TCP")
        void shouldCreateEnvelopeWithCorrectProtocolAndTransport() {
            adapter.handle(mockAstmMessage, "192.168.1.10");

            verify(mockNormalizer).process(argThat(envelope ->
                envelope.getProtocol() == Protocol.ASTM &&
                envelope.getTransport() == Transport.TCP
            ));
        }

        @Test
        @DisplayName("Should use provided sourceIp in envelope")
        void shouldUseProvidedSourceIpInEnvelope() {
            String sourceIp = "192.168.1.10";

            adapter.handle(mockAstmMessage, sourceIp);

            verify(mockNormalizer).process(argThat(envelope ->
                sourceIp.equals(envelope.getSourceId())
            ));
        }

        @Test
        @DisplayName("Connection-owned listener uses its stable source binding")
        void connectionOwnedListenerUsesItsStableSourceBinding() {
            ASTMBridgeAdapter boundAdapter = new ASTMBridgeAdapter(
                mockNormalizer,
                "connection:bridge-42"
            );

            boundAdapter.handle(mockAstmMessage, "192.168.1.10");

            verify(mockNormalizer).process(argThat(envelope ->
                "connection:bridge-42".equals(envelope.getSourceId())
            ));
        }

        @Test
        @DisplayName("Should use 'unknown' as sourceId when sourceIp is null")
        void shouldUseUnknownWhenSourceIpIsNull() {
            adapter.handle(mockAstmMessage, null);

            verify(mockNormalizer).process(argThat(envelope ->
                "unknown".equals(envelope.getSourceId())
            ));
        }

        @Test
        @DisplayName("Should include raw message in envelope")
        void shouldIncludeRawMessageInEnvelope() {
            adapter.handle(mockAstmMessage, "192.168.1.10");

            verify(mockNormalizer).process(argThat(envelope ->
                SAMPLE_ASTM_MESSAGE.equals(envelope.getRawMessage())
            ));
        }

        @Test
        @DisplayName("Should call normalizer.process() exactly once")
        void shouldCallNormalizerProcessOnce() {
            adapter.handle(mockAstmMessage, "192.168.1.10");

            verify(mockNormalizer, times(1)).process(any(MessageEnvelope.class));
        }

        @Test
        @DisplayName("Handshake-only sessions should not enter result processing")
        void handshakeOnlySessionsShouldNotEnterResultProcessing() {
            when(mockAstmMessage.getMessage()).thenReturn("");

            ASTMHandlerResponse response = adapter.handle(mockAstmMessage, "192.168.1.10");

            assertEquals(HandleStatus.SUCCESS, response.getStatus());
            verify(mockNormalizer, never()).process(any(MessageEnvelope.class));
        }
    }

    @Nested
    @DisplayName("Response Tests")
    class ResponseTests {

        @Test
        @DisplayName("Should return SUCCESS status when normalizer returns true")
        void shouldReturnSuccessWhenNormalizerReturnsTrue() {
            when(mockNormalizer.process(any())).thenReturn(true);

            ASTMHandlerResponse response = adapter.handle(mockAstmMessage, "192.168.1.10");

            assertNotNull(response);
            assertEquals(HandleStatus.SUCCESS, response.getStatus());
        }

        @Test
        @DisplayName("Should return FORWARD_FAIL_ERROR status when normalizer returns false")
        void shouldReturnForwardFailErrorWhenNormalizerReturnsFalse() {
            when(mockNormalizer.process(any())).thenReturn(false);

            ASTMHandlerResponse response = adapter.handle(mockAstmMessage, "192.168.1.10");

            assertNotNull(response);
            assertEquals(HandleStatus.FORWARD_FAIL_ERROR, response.getStatus());
        }

        @Test
        @DisplayName("Should return empty response string")
        void shouldReturnEmptyResponseString() {
            ASTMHandlerResponse response = adapter.handle(mockAstmMessage, "192.168.1.10");

            assertEquals("", response.getResponse());
        }

        @Test
        @DisplayName("Should set communicateResponse to false")
        void shouldSetCommunicateResponseToFalse() {
            ASTMHandlerResponse response = adapter.handle(mockAstmMessage, "192.168.1.10");

            assertFalse(response.getCommunicateResponse());
        }

        @Test
        @DisplayName("Should return adapter instance as handler")
        void shouldReturnAdapterInstanceAsHandler() {
            ASTMHandlerResponse response = adapter.handle(mockAstmMessage, "192.168.1.10");

            assertSame(adapter, response.getHandler());
        }
    }

    @Nested
    @DisplayName("Integration Tests with Real Messages")
    class IntegrationTests {

        @Test
        @DisplayName("Should handle complete ASTM result message")
        void shouldHandleCompleteASTMResultMessage() {
            String completeMessage =
                "H|\\^&|||MINDRAY^BC-5380^12345|||||||P|1|20260205120000\r" +
                "P|1||PAT001||DOE^JOHN||19800101|M\r" +
                "O|1|SAM001||^^^CBC|R||20260205120000\r" +
                "R|1|^^^WBC|7.5|10^3/uL|4.0-11.0|N||F|||20260205120000\r" +
                "R|2|^^^RBC|4.8|10^6/uL|4.5-5.5|N||F|||20260205120000\r" +
                "R|3|^^^HGB|14.2|g/dL|12.0-16.0|N||F|||20260205120000\r" +
                "L|1|N";

            when(mockAstmMessage.getMessage()).thenReturn(completeMessage);

            ASTMHandlerResponse response = adapter.handle(mockAstmMessage, "192.168.1.20");

            assertEquals(HandleStatus.SUCCESS, response.getStatus());
            verify(mockNormalizer).process(argThat(envelope ->
                completeMessage.equals(envelope.getRawMessage()) &&
                "192.168.1.20".equals(envelope.getSourceId())
            ));
        }

        @Test
        @DisplayName("Should handle message with special characters")
        void shouldHandleMessageWithSpecialCharacters() {
            String messageWithSpecialChars =
                "H|\\^&|||TEST^with\\special|characters~here\r" +
                "P|1||ID&with^special|chars\r" +
                "L|1|N";

            when(mockAstmMessage.getMessage()).thenReturn(messageWithSpecialChars);

            ASTMHandlerResponse response = adapter.handle(mockAstmMessage, "192.168.1.30");

            assertEquals(HandleStatus.SUCCESS, response.getStatus());
            verify(mockNormalizer).process(argThat(envelope ->
                messageWithSpecialChars.equals(envelope.getRawMessage())
            ));
        }

        @Test
        @DisplayName("Should handle IPv6 source address")
        void shouldHandleIPv6SourceAddress() {
            String ipv6Address = "2001:0db8:85a3:0000:0000:8a2e:0370:7334";

            ASTMHandlerResponse response = adapter.handle(mockAstmMessage, ipv6Address);

            assertEquals(HandleStatus.SUCCESS, response.getStatus());
            verify(mockNormalizer).process(argThat(envelope ->
                ipv6Address.equals(envelope.getSourceId())
            ));
        }
    }
}
