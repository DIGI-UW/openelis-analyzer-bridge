package org.itech.ahb.normalizer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

import java.util.Map;
import org.itech.ahb.connection.AnalyzerRuntimeRegistry;
import org.itech.ahb.model.Protocol;
import org.itech.ahb.model.Transport;
import org.itech.ahb.routing.HttpForwardingRouter;
import java.nio.file.Path;
import java.util.List;
import org.itech.ahb.outbox.FailureReason;
import org.itech.ahb.outbox.OutboxEntry;
import org.itech.ahb.outbox.OutboxQuery;
import org.itech.ahb.outbox.OutboxState;
import org.itech.ahb.outbox.OutboxStore;
import org.itech.ahb.outbox.SqliteOutboxStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
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

    /**
     * A real store on a temp directory rather than a mock: persistence is the behavior under test
     * here, and a mock would let a change that silently stops persisting still pass.
     */
    @TempDir
    Path outboxDir;

    private OutboxStore outbox;

    @BeforeEach
    void setUp() {
        outbox = new SqliteOutboxStore(outboxDir.resolve("outbox.db"));
        normalizer = new MessageNormalizer(mockForwardingRouter, mockIdentifier, outbox, null, null);
        // Lenient: some tests override these stubs
        lenient().when(mockForwardingRouter.route(any(MessageEnvelope.class))).thenReturn(true);
        lenient().when(mockIdentifier.identify(any(MessageEnvelope.class))).thenReturn("DEFAULT-ANALYZER");
    }

    @AfterEach
    void closeOutbox() {
        if (outbox != null) {
            outbox.close();
        }
    }

    @Nested
    @DisplayName("Analyzer Identification Tests")
    class AnalyzerIdentificationTests {

        @Test
        @DisplayName("Should route when resolved analyzer and protocol hint agree")
        void shouldRouteWhenResolvedAnalyzerAndHintAgree() {
            when(mockIdentifier.identify(any())).thenReturn("MINDRAY-001");

            MessageEnvelope envelope = MessageEnvelope.builder()
                .protocol(Protocol.ASTM)
                .transport(Transport.SERIAL)
                .sourceId("/dev/ttyUSB0")
                .rawMessage("H|\\^&|||TEST")
                .protocolAnalyzerHint("MINDRAY-001")
                .build();

            boolean result = normalizer.process(envelope);

            assertTrue(result);
            // Verify forwardingRouter is called with canonical resolved analyzer ID.
            verify(mockForwardingRouter).route(argThat(e ->
                "MINDRAY-001".equals(e.getResolvedAnalyzerId()) &&
                "MINDRAY-001".equals(e.getProtocolAnalyzerHint())
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
                "SYSMEX-001".equals(e.getResolvedAnalyzerId()) &&
                Protocol.HL7.equals(e.getProtocol()) &&
                "192.168.1.10".equals(e.getSourceId())
            ));
        }

        @Test
        @DisplayName("Should reject an unregistered source before forwarding")
        void shouldRejectWhenIdentifierReturnsNull() {
            when(mockIdentifier.identify(any())).thenReturn(null);

            MessageEnvelope envelope = MessageEnvelope.builder()
                .protocol(Protocol.CSV)
                .transport(Transport.FILE)
                .sourceId("/mnt/analyzer/file.csv")
                .rawMessage("SampleID,TestCode,Result")
                .build();

            boolean result = normalizer.process(envelope);

            assertFalse(result);
            verifyNoInteractions(mockForwardingRouter);
        }

        @Test
        @DisplayName("Should route when protocol hint conflicts but source registration resolves analyzer")
        void shouldRouteOnHintConflictWhenSourceRegistrationResolvesAnalyzer() {
            when(mockIdentifier.identify(any())).thenReturn("OE-ANALYZER-001");

            MessageEnvelope envelope = MessageEnvelope.builder()
                .protocol(Protocol.HL7)
                .transport(Transport.MLLP)
                .sourceId("192.168.1.10")
                .rawMessage("MSH|^~\\&|||")
                .protocolAnalyzerHint("GENEXPERT")
                .build();

            boolean result = normalizer.process(envelope);

            assertTrue(result);
            verify(mockForwardingRouter).route(argThat(e ->
                "OE-ANALYZER-001".equals(e.getResolvedAnalyzerId()) &&
                "GENEXPERT".equals(e.getProtocolAnalyzerHint())
            ));
        }

        @Test
        @DisplayName("Should validate hint against registered analyzer metadata namespace")
        void shouldValidateHintAgainstRegisteredAnalyzerMetadata() {
            when(mockIdentifier.identify(any())).thenReturn("44");

            AnalyzerRuntimeRegistry registry = new AnalyzerRuntimeRegistry();
            AnalyzerRuntimeRegistry.AnalyzerEntry entry = new AnalyzerRuntimeRegistry.AnalyzerEntry();
            entry.setId("44");
            entry.setName("Demo: GeneXpert ASTM");
            entry.setExpectedProtocol("ASTM");
            entry.setInboundTransport("TCP/IP");
            registry.register("10.42.59.10", entry);

            MessageNormalizer metadataAwareNormalizer =
                new MessageNormalizer(mockForwardingRouter, mockIdentifier, outbox, registry, null);

            MessageEnvelope envelope = MessageEnvelope.builder()
                .protocol(Protocol.ASTM)
                .transport(Transport.TCP)
                .sourceId("10.42.59.10")
                .rawMessage("H|\\^&|||GENEXPERT")
                .protocolAnalyzerHint("GENEXPERT")
                .build();

            boolean result = metadataAwareNormalizer.process(envelope);

            assertTrue(result);
            verify(mockForwardingRouter).route(argThat(e ->
                "44".equals(e.getResolvedAnalyzerId()) &&
                "GENEXPERT".equals(e.getProtocolAnalyzerHint())
            ));
        }

        @Test
        @DisplayName("Should not use a protocol hint as routing authority")
        void shouldRejectWhenOnlyProtocolHintPresent() {
            when(mockIdentifier.identify(any())).thenReturn(null);

            MessageEnvelope envelope = MessageEnvelope.builder()
                .protocol(Protocol.HL7)
                .transport(Transport.MLLP)
                .sourceId("192.168.1.10")
                .rawMessage("MSH|^~\\&|||")
                .protocolAnalyzerHint("SYSMEX")
                .build();

            boolean result = normalizer.process(envelope);

            assertFalse(result);
            verifyNoInteractions(mockForwardingRouter);
        }
    }

    @Nested
    @DisplayName("Routing Tests")
    class ConnectionTransportTests {

      @Test
      void rejectsSavedProtocolOrTransportMismatchBeforeForwarding() {
        AnalyzerRuntimeRegistry registry = new AnalyzerRuntimeRegistry();
        var entry = new AnalyzerRuntimeRegistry.AnalyzerEntry();
        entry.setId("DEFAULT-ANALYZER");
        entry.setExpectedProtocol("FILE");
        entry.setInboundTransport("HTTP");
        registry.register("connection:test", entry);
        var bound = new MessageNormalizer(mockForwardingRouter, mockIdentifier, outbox, registry, null);
        for (MessageEnvelope envelope : new MessageEnvelope[] {
          MessageEnvelope.builder()
            .protocol(Protocol.HL7)
            .transport(Transport.HTTP)
            .sourceId("connection:test")
            .rawMessage("MSH|^~\\&|||")
            .build(),
          MessageEnvelope.builder()
            .protocol(Protocol.CSV)
            .transport(Transport.FILE)
            .sourceId("connection:test")
            .rawMessage("sample,result\n1,2")
            .build(),
          MessageEnvelope.builder()
            .protocol(Protocol.ASTM)
            .transport(Transport.HTTP)
            .sourceId("connection:test")
            .rawMessage("H|\\^&|||TEST\rQ|1|sample\rL|1|N")
            .build()
        }) {
          assertFalse(bound.process(envelope));
        }
        verifyNoInteractions(mockForwardingRouter);
      }

      @Test
      void acceptsPinnedProtocolTransportPairs() {
        for (Object[] pair : new Object[][] {
          { "FILE", "HTTP", Protocol.CSV, Transport.HTTP },
          { "FILE", "FILE", Protocol.CSV, Transport.FILE },
          { "ASTM", "TCP/IP", Protocol.ASTM, Transport.TCP },
          { "ASTM", "RS-232", Protocol.ASTM, Transport.SERIAL },
          { "HL7", "TCP/IP", Protocol.HL7, Transport.MLLP }
        }) {
          AnalyzerRuntimeRegistry registry = new AnalyzerRuntimeRegistry();
          var entry = new AnalyzerRuntimeRegistry.AnalyzerEntry();
          entry.setId("DEFAULT-ANALYZER");
          entry.setExpectedProtocol((String) pair[0]);
          entry.setInboundTransport((String) pair[1]);
          registry.register("connection:test", entry);
          var bound = new MessageNormalizer(mockForwardingRouter, mockIdentifier, outbox, registry, null);
          assertTrue(
            bound.process(
              MessageEnvelope.builder()
                .protocol((Protocol) pair[2])
                .transport((Transport) pair[3])
                .sourceId("connection:test")
                .rawMessage("result")
                .build()
            )
          );
        }
        verify(mockForwardingRouter, times(5)).route(any());
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

        @Test
        @DisplayName("Should preserve sourcePort in enriched envelope")
        void shouldPreserveSourcePort() {
            when(mockIdentifier.identify(any())).thenReturn("ANALYZER-005");

            MessageEnvelope envelope = MessageEnvelope.builder()
                .protocol(Protocol.HL7)
                .transport(Transport.MLLP)
                .sourceId("192.168.1.60")
                .rawMessage("MSH|^~\\&|||")
                .sourcePort(54321)
                .build();

            normalizer.process(envelope);

            verify(mockForwardingRouter).route(argThat(e ->
                Integer.valueOf(54321).equals(e.getSourcePort())
            ));
        }

        @Test
        @DisplayName("Should preserve null sourcePort in enriched envelope")
        void shouldPreserveNullSourcePort() {
            when(mockIdentifier.identify(any())).thenReturn("ANALYZER-006");

            MessageEnvelope envelope = MessageEnvelope.builder()
                .protocol(Protocol.ASTM)
                .transport(Transport.TCP)
                .sourceId("192.168.1.70")
                .rawMessage("H|\\^&|||TEST")
                .build();

            normalizer.process(envelope);

            verify(mockForwardingRouter).route(argThat(e ->
                e.getSourcePort() == null
            ));
        }
    }

    @Nested
    @DisplayName("Unknown source rejection")
    class UnknownSourceRejectionTests {

        private MessageNormalizer normalizerWithUnknownSource;

        private AnalyzerIdentifier rejectingIdentifier;

        @BeforeEach
        void setUp() {
            rejectingIdentifier = mock(AnalyzerIdentifier.class);
            // Note: per-test stub `rejectingIdentifier.identify` because the
            // Q-only-message path short-circuits before identifier is called
            // (avoids Mockito strict-mode UnnecessaryStubbing failures).
            normalizerWithUnknownSource = new MessageNormalizer(
                mockForwardingRouter, rejectingIdentifier, outbox, null, null);
        }

        @Test
        @DisplayName("Rejects an unknown-source message but keeps it, complete, in the dead-message queue")
        void shouldRejectAndDeadLetterUnknownSource() {
            when(rejectingIdentifier.identify(any())).thenReturn(null);

            MessageEnvelope envelope = MessageEnvelope.builder()
                .protocol(Protocol.HL7)
                .transport(Transport.MLLP)
                .sourceId("10.0.0.50")
                .rawMessage("MSH|^~\\&|||UNKNOWN-DEVICE")
                .build();

            boolean result = normalizerWithUnknownSource.process(envelope);

            assertFalse(result);
            verifyNoInteractions(mockForwardingRouter);
            List<OutboxEntry> dead = outbox.list(OutboxQuery.inState(OutboxState.DMQ, 10));
            assertEquals(1, dead.size(), "an unidentifiable message is still a received result and must be kept");
            assertEquals(FailureReason.UNREGISTERED_SOURCE, dead.get(0).failureReason());
            assertEquals(
                "MSH|^~\\&|||UNKNOWN-DEVICE",
                outbox.rawPayload(dead.get(0).id()).orElseThrow(),
                "the operator needs the whole message to decide what to register");
        }

        @Test
        @DisplayName("Drops ASTM Q-only messages (queries) without calling parser or DLQ")
        void shouldSkipAstmQueryOnlyMessages() {
            // Real GeneXpert query format: H|...|Q|...|L|
            String astmQuery = "H|@^\\|GXM-12345|||LA2M3^GeneXpert^6.2|||||geneexpert||P|1394-97|20260414155743\r"
                + "Q|1|ALL||||||||||O@N\r"
                + "L|1|N\r";
            MessageEnvelope envelope = MessageEnvelope.builder()
                .protocol(Protocol.ASTM)
                .transport(Transport.TCP)
                .sourceId("10.0.0.50")
                .rawMessage(astmQuery)
                .build();

            boolean result = normalizerWithUnknownSource.process(envelope);

            // Q-only messages are intentionally not forwarded — no R-records
            // to translate. Treated as "successfully handled" so callers
            // don't retry / log errors.
            assertTrue(result);
            verify(mockForwardingRouter, never()).route(any(MessageEnvelope.class));
            assertTrue(
                outbox.list(OutboxQuery.all(10)).isEmpty(),
                "a query carries no result, so storing it would only fill the queue with undeliverable traffic");
        }
    }
}
