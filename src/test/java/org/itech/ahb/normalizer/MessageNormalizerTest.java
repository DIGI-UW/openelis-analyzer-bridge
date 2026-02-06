package org.itech.ahb.normalizer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

import org.itech.ahb.model.Protocol;
import org.itech.ahb.model.Transport;
import org.itech.ahb.routing.HttpForwardingRouter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for MessageNormalizer.
 * <p>
 * Tests the central orchestration service that implements MessageRouter
 * and delegates to HttpForwardingRouter for actual routing.
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class MessageNormalizerTest {

    @Mock
    private HttpForwardingRouter mockForwardingRouter;

    @Mock
    private AnalyzerIdentifier mockIdentifier;

    private MessageNormalizer normalizer;

    @BeforeEach
    void setUp() {
        normalizer = new MessageNormalizer(mockForwardingRouter, mockIdentifier);
        // Lenient: some tests override these stubs
        lenient().when(mockForwardingRouter.route(any(MessageEnvelope.class))).thenReturn(true);
        lenient().when(mockIdentifier.identify(any(MessageEnvelope.class))).thenReturn(null);
    }

    @Nested
    @DisplayName("Analyzer Identification Tests")
    class AnalyzerIdentificationTests {

        @Test
        @DisplayName("Should use existing analyzer ID when envelope has one")
        void shouldUseExistingAnalyzerId() {
            MessageEnvelope envelope = MessageEnvelope.builder()
                .protocol(Protocol.ASTM)
                .transport(Transport.SERIAL)
                .sourceId("/dev/ttyUSB0")
                .rawMessage("H|\\^&|||TEST")
                .analyzerId("MINDRAY-001")
                .build();

            boolean result = normalizer.process(envelope);

            assertTrue(result);
            // Verify forwardingRouter is called with original envelope (analyzer ID unchanged)
            verify(mockForwardingRouter).route(argThat(e ->
                "MINDRAY-001".equals(e.getAnalyzerId())
            ));
        }

        @Test
        @DisplayName("Should enrich envelope when identifier returns analyzer ID")
        void shouldEnrichEnvelopeWithIdentifiedAnalyzerId() {
            when(mockIdentifier.identify(any())).thenReturn("SYSMEX-001");

            MessageEnvelope envelope = MessageEnvelope.builder()
                .protocol(Protocol.HL7)
                .transport(Transport.TCP)
                .sourceId("192.168.1.10")
                .rawMessage("MSH|^~\\&|||")
                .build();

            boolean result = normalizer.process(envelope);

            assertTrue(result);
            // Verify forwardingRouter is called with enriched envelope
            verify(mockForwardingRouter).route(argThat(e ->
                "SYSMEX-001".equals(e.getAnalyzerId()) &&
                Protocol.HL7.equals(e.getProtocol()) &&
                "192.168.1.10".equals(e.getSourceId())
            ));
        }

        @Test
        @DisplayName("Should use original envelope when identifier returns null")
        void shouldUseOriginalEnvelopeWhenIdentifierReturnsNull() {
            when(mockIdentifier.identify(any())).thenReturn(null);

            MessageEnvelope envelope = MessageEnvelope.builder()
                .protocol(Protocol.CSV)
                .transport(Transport.FILE)
                .sourceId("/mnt/analyzer/file.csv")
                .rawMessage("SampleID,TestCode,Result")
                .build();

            boolean result = normalizer.process(envelope);

            assertTrue(result);
            // Verify forwardingRouter is called with original envelope
            verify(mockForwardingRouter).route(argThat(e ->
                e.getAnalyzerId() == null &&
                Protocol.CSV.equals(e.getProtocol())
            ));
        }
    }

    @Nested
    @DisplayName("Routing Tests")
    class RoutingTests {

        @Test
        @DisplayName("Should return true when forwarding router succeeds")
        void shouldReturnTrueWhenForwardingRouterSucceeds() {
            when(mockForwardingRouter.route(any())).thenReturn(true);

            MessageEnvelope envelope = MessageEnvelope.builder()
                .protocol(Protocol.ASTM)
                .transport(Transport.SERIAL)
                .sourceId("/dev/ttyUSB0")
                .rawMessage("H|\\^&|||TEST")
                .build();

            boolean result = normalizer.process(envelope);

            assertTrue(result);
        }

        @Test
        @DisplayName("Should return false when forwarding router fails")
        void shouldReturnFalseWhenForwardingRouterFails() {
            when(mockForwardingRouter.route(any())).thenReturn(false);

            MessageEnvelope envelope = MessageEnvelope.builder()
                .protocol(Protocol.ASTM)
                .transport(Transport.SERIAL)
                .sourceId("/dev/ttyUSB0")
                .rawMessage("H|\\^&|||TEST")
                .build();

            boolean result = normalizer.process(envelope);

            assertFalse(result);
        }

        @Test
        @DisplayName("Should call forwardingRouter.route() exactly once")
        void shouldCallForwardingRouterOnce() {
            MessageEnvelope envelope = MessageEnvelope.builder()
                .protocol(Protocol.HL7)
                .transport(Transport.MLLP)
                .sourceId("192.168.1.20")
                .rawMessage("MSH|^~\\&|||")
                .build();

            normalizer.process(envelope);

            verify(mockForwardingRouter, times(1)).route(any(MessageEnvelope.class));
        }
    }

    @Nested
    @DisplayName("MessageRouter Implementation Tests")
    class MessageRouterImplementationTests {

        @Test
        @DisplayName("route() method should delegate to process()")
        void routeShouldDelegateToProcess() {
            when(mockForwardingRouter.route(any())).thenReturn(true);

            MessageEnvelope envelope = MessageEnvelope.builder()
                .protocol(Protocol.ASTM)
                .transport(Transport.TCP)
                .sourceId("192.168.1.30")
                .rawMessage("H|\\^&|||TEST")
                .build();

            // Call route() (MessageRouter interface method)
            boolean result = normalizer.route(envelope);

            assertTrue(result);
            // Verify forwardingRouter was called (via process())
            verify(mockForwardingRouter).route(any(MessageEnvelope.class));
        }

        @Test
        @DisplayName("route() should return same result as process()")
        void routeShouldReturnSameResultAsProcess() {
            when(mockForwardingRouter.route(any())).thenReturn(false);

            MessageEnvelope envelope = MessageEnvelope.builder()
                .protocol(Protocol.CSV)
                .transport(Transport.FILE)
                .sourceId("/mnt/file.csv")
                .rawMessage("SampleID,TestCode")
                .build();

            // Both should return same result
            boolean routeResult = normalizer.route(envelope);
            boolean processResult = normalizer.process(envelope);

            assertEquals(routeResult, processResult);
            assertFalse(routeResult);
            assertFalse(processResult);
        }
    }

    @Nested
    @DisplayName("Envelope Preservation Tests")
    class EnvelopePreservationTests {

        @Test
        @DisplayName("Should preserve protocol in enriched envelope")
        void shouldPreserveProtocol() {
            when(mockIdentifier.identify(any())).thenReturn("ANALYZER-001");

            MessageEnvelope envelope = MessageEnvelope.builder()
                .protocol(Protocol.HL7)
                .transport(Transport.HTTP)
                .sourceId("192.168.1.40")
                .rawMessage("MSH|^~\\&|||")
                .build();

            normalizer.process(envelope);

            verify(mockForwardingRouter).route(argThat(e ->
                Protocol.HL7.equals(e.getProtocol())
            ));
        }

        @Test
        @DisplayName("Should preserve transport in enriched envelope")
        void shouldPreserveTransport() {
            when(mockIdentifier.identify(any())).thenReturn("ANALYZER-002");

            MessageEnvelope envelope = MessageEnvelope.builder()
                .protocol(Protocol.ASTM)
                .transport(Transport.SERIAL)
                .sourceId("/dev/ttyUSB1")
                .rawMessage("H|\\^&|||TEST")
                .build();

            normalizer.process(envelope);

            verify(mockForwardingRouter).route(argThat(e ->
                Transport.SERIAL.equals(e.getTransport())
            ));
        }

        @Test
        @DisplayName("Should preserve sourceId in enriched envelope")
        void shouldPreserveSourceId() {
            when(mockIdentifier.identify(any())).thenReturn("ANALYZER-003");

            MessageEnvelope envelope = MessageEnvelope.builder()
                .protocol(Protocol.CSV)
                .transport(Transport.FILE)
                .sourceId("/mnt/quantstudio/results.csv")
                .rawMessage("SampleID,TestCode,Result")
                .build();

            normalizer.process(envelope);

            verify(mockForwardingRouter).route(argThat(e ->
                "/mnt/quantstudio/results.csv".equals(e.getSourceId())
            ));
        }

        @Test
        @DisplayName("Should preserve rawMessage in enriched envelope")
        void shouldPreserveRawMessage() {
            when(mockIdentifier.identify(any())).thenReturn("ANALYZER-004");

            String rawMessage = "H|\\^&|||TEST||||||P|1\rP|1||12345\rL|1|N";
            MessageEnvelope envelope = MessageEnvelope.builder()
                .protocol(Protocol.ASTM)
                .transport(Transport.TCP)
                .sourceId("192.168.1.50")
                .rawMessage(rawMessage)
                .build();

            normalizer.process(envelope);

            verify(mockForwardingRouter).route(argThat(e ->
                rawMessage.equals(e.getRawMessage())
            ));
        }
    }
}
