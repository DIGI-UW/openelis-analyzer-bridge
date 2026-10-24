package org.itech.ahb.normalizer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import org.itech.ahb.connection.AnalyzerRuntimeRegistry;
import org.itech.ahb.model.Protocol;
import org.itech.ahb.model.Transport;
import org.itech.ahb.outbox.*;
import org.itech.ahb.routing.HttpForwardingRouter;
import org.itech.ahb.routing.ReceivedInterpretation;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

/** Receipt owns the identity decision; forwarding consumes the retained decision. */
class MessageNormalizerTest {

  @TempDir
  Path directory;

  private SqliteOutboxStore outbox;
  private AnalyzerRuntimeRegistry registry;
  private HttpForwardingRouter mockForwardingRouter;
  private MessageNormalizer normalizer;

  @BeforeEach
  void setUp() {
    outbox = new SqliteOutboxStore(directory.resolve("outbox.db"));
    registry = new AnalyzerRuntimeRegistry();
    register("first", "HL7", "TCP/IP");
    mockForwardingRouter = mock(HttpForwardingRouter.class);
    when(mockForwardingRouter.route(any())).thenReturn(true);
    normalizer = new MessageNormalizer(mockForwardingRouter, outbox, registry, null);
  }

  @AfterEach
  void close() {
    outbox.close();
  }

  private void register(String id, String protocol, String transport) {
    var entry = new AnalyzerRuntimeRegistry.AnalyzerEntry();
    entry.setId(id);
    entry.setExpectedProtocol(protocol);
    entry.setInboundTransport(transport);
    registry.register("connection:test", entry);
  }

  private MessageEnvelope message() {
    return MessageEnvelope.builder()
      .protocol(Protocol.HL7)
      .transport(Transport.MLLP)
      .sourceId("connection:test")
      .sourcePort(1234)
      .rawMessage("MSH|^~\\&|Analyzer|Lab\rOBX|1|NM|WBC||7.5\r")
      .protocolAnalyzerHint("Analyzer")
      .build();
  }

  @Test
  void persistsIdentityAndPayloadBeforeForwarding() {
    var input = message();
    assertTrue(normalizer.process(input));
    var forwarded = ArgumentCaptor.forClass(MessageEnvelope.class);
    verify(mockForwardingRouter).route(forwarded.capture());
    var actual = forwarded.getValue();
    assertEquals(input.getRawMessage(), outbox.rawPayload(actual.getOutboxReceiptId()).orElseThrow());
    assertEquals(
      "first",
      ReceivedInterpretation.read(outbox.interpretationContext(actual.getOutboxReceiptId()).orElseThrow())
        .analyzer()
        .getId()
    );
    assertEquals("first", actual.getResolvedAnalyzerId());
    assertEquals(input.getSourcePort(), actual.getSourcePort());
    assertEquals(input.getReceivedAt(), actual.getReceivedAt());
    assertEquals(input.getProtocol(), actual.getProtocol());
    assertEquals(input.getTransport(), actual.getTransport());
  }

  @Test
  void anAcknowledgedReceiptKeepsItsIdentityAfterConfigurationChanges() {
    var input = message();
    normalizer.persistBeforeAcknowledgement(input);
    register("replacement", "ASTM", "HTTP");
    assertTrue(normalizer.process(input));
    var forwarded = ArgumentCaptor.forClass(MessageEnvelope.class);
    verify(mockForwardingRouter).route(forwarded.capture());
    assertEquals("first", forwarded.getValue().getResolvedAnalyzerId());
  }

  @Test
  void unknownSenderIsRetainedButNeverForwarded() {
    normalizer = new MessageNormalizer(mockForwardingRouter, outbox, new AnalyzerRuntimeRegistry(), null);
    assertFalse(normalizer.process(message()));
    var held = outbox.list(OutboxQuery.inState(OutboxState.DMQ, 10));
    assertEquals(1, held.size());
    assertEquals(FailureReason.UNREGISTERED_SOURCE, held.get(0).failureReason());
    assertEquals(message().getRawMessage(), outbox.rawPayload(held.get(0).id()).orElseThrow());
    verifyNoInteractions(mockForwardingRouter);
  }

  @Test
  void incompatibleTransportIsRetainedAsUnresolved() {
    register("first", "ASTM", "TCP/IP");
    assertFalse(normalizer.process(message()));
    var held = outbox.list(OutboxQuery.inState(OutboxState.DMQ, 10)).get(0);
    assertEquals(FailureReason.CONNECTION_TRANSPORT_MISMATCH, held.failureReason());
    assertNull(ReceivedInterpretation.read(outbox.interpretationContext(held.id()).orElseThrow()).analyzer());
    verifyNoInteractions(mockForwardingRouter);
  }

  @Test
  void downstreamFailureDoesNotLoseTheReceipt() {
    when(mockForwardingRouter.route(any())).thenReturn(false);
    assertFalse(normalizer.route(message()));
    var forwarded = ArgumentCaptor.forClass(MessageEnvelope.class);
    verify(mockForwardingRouter).route(forwarded.capture());
    assertTrue(outbox.rawPayload(forwarded.getValue().getOutboxReceiptId()).isPresent());
  }

  @Test
  void queryOnlyMessagesAreNotResults() {
    register("first", "ASTM", "TCP/IP");
    var query = MessageEnvelope.builder()
      .protocol(Protocol.ASTM)
      .transport(Transport.TCP)
      .sourceId("connection:test")
      .rawMessage("H|^~\\&|||Analyzer\rQ|1|ALL\rL|1\r")
      .build();
    assertTrue(normalizer.process(query));
    verifyNoInteractions(mockForwardingRouter);
    assertTrue(MessageNormalizer.isQueryOnlyAstmMessage("H;^~\\&;;;Analyzer\rQ;1;ALL\rL;1\r"));
    assertFalse(MessageNormalizer.isQueryOnlyAstmMessage("H|^~\\&\rQ|1|ALL\rR|1|WBC|7.5\r"));
  }

  @Test
  void invalidEnvelopeIsRefusedBeforeForwarding() {
    assertFalse(normalizer.process(null));
    assertFalse(normalizer.process(MessageEnvelope.builder().build()));
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
      var bound = new MessageNormalizer(mockForwardingRouter, outbox, registry, null);
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
