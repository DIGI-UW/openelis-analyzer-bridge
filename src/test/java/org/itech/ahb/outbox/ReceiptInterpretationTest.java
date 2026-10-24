package org.itech.ahb.outbox;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Instant;
import org.itech.ahb.connection.AnalyzerRuntimeRegistry;
import org.itech.ahb.model.Protocol;
import org.itech.ahb.model.Transport;
import org.itech.ahb.normalizer.MessageEnvelope;
import org.itech.ahb.profile.ResultReading;
import org.itech.ahb.routing.ReceivedInterpretation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReceiptInterpretationTest {

  @TempDir
  Path directory;

  @Test
  void restartUsesTheReceiptSettingsAndRetryCannotReplaceThem() throws Exception {
    var registry = new AnalyzerRuntimeRegistry();
    var analyzer = new AnalyzerRuntimeRegistry.AnalyzerEntry();
    analyzer.setId("analyzer");
    analyzer.setBridgeConnectionId("connection");
    analyzer.setExpectedProtocol("ASTM");
    analyzer.setInboundTransport("TCP/IP");
    var profile = new ObjectMapper()
      .readTree("{\"protocol\":{\"name\":\"ASTM\"},\"configDefaults\":{\"numberFormat\":\",\"}}");
    analyzer.setResultReading(ResultReading.fromProfile(profile));
    registry.register("host", analyzer);
    var envelope = MessageEnvelope.builder()
      .sourceId("host")
      .protocol(Protocol.ASTM)
      .transport(Transport.TCP)
      .rawMessage("H|raw\rL|1")
      .receivedAt(Instant.now())
      .build();
    String captured = ReceivedInterpretation.capture(registry, envelope);
    String id;
    Path db = directory.resolve("outbox.db");
    try (var store = new SqliteOutboxStore(db)) {
      id = store
        .receive(
          new ReceivedMessage(
            "host",
            1,
            Protocol.ASTM,
            Transport.TCP,
            null,
            envelope.getRawMessage(),
            null,
            Instant.now(),
            null,
            captured
          )
        )
        .id();
    }
    analyzer.setResultReading(
      ResultReading.fromProfile(new ObjectMapper().readTree("{\"protocol\":{\"name\":\"ASTM\"}}"))
    );
    try (var store = new SqliteOutboxStore(db)) {
      store.recoverInterrupted(Instant.now());
      var retained = ReceivedInterpretation.read(store.interpretationContext(id).orElseThrow());
      assertEquals("1.23", retained.analyzer().getResultReading().canonicalNumber("1,23"));
      assertEquals("1,23", analyzer.getResultReading().canonicalNumber("1,23"));
      assertThrows(
        IllegalStateException.class,
        () -> store.resolveInterpretation(id, ReceivedInterpretation.capture(registry, envelope))
      );
      assertEquals(captured, store.interpretationContext(id).orElseThrow());
    }
  }
}
