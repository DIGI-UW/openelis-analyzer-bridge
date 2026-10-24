package org.itech.ahb.routing;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.itech.ahb.connection.AnalyzerRuntimeRegistry;
import org.itech.ahb.fhir.HL7ResultParser;
import org.itech.ahb.model.Protocol;
import org.itech.ahb.normalizer.MessageEnvelope;
import org.itech.ahb.outbox.FailureReason;

/** Immutable receipt evidence, stored atomically with raw input before acknowledgement. */
public record ReceivedInterpretation(
  int version,
  AnalyzerRuntimeRegistry.AnalyzerEntry analyzer,
  FailureReason reason,
  String detail
) {
  private static final ObjectMapper JSON = new ObjectMapper()
    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

  public ReceivedInterpretation {
    if (version != 1) throw new IllegalArgumentException("Unsupported receipt interpretation version");
  }

  public static String capture(AnalyzerRuntimeRegistry registry, MessageEnvelope envelope) {
    String hint = envelope.getProtocol() == Protocol.HL7
      ? HL7ResultParser.sendingApplication(envelope.getRawMessage())
      : envelope.getProtocolAnalyzerHint();
    var resolution = registry == null
      ? null
      : registry.resolve(envelope.getListenerPort(), envelope.getSourceId(), hint);
    ReceivedInterpretation context = resolution instanceof AnalyzerRuntimeRegistry.Resolution.Resolved resolved
      ? new ReceivedInterpretation(1, resolved.entry(), null, null)
      : resolution instanceof AnalyzerRuntimeRegistry.Resolution.Ambiguous ambiguous
        ? new ReceivedInterpretation(1, null, FailureReason.AMBIGUOUS_SOURCE, ambiguous.detail())
        : new ReceivedInterpretation(
          1,
          null,
          FailureReason.UNREGISTERED_SOURCE,
          resolution instanceof AnalyzerRuntimeRegistry.Resolution.Unregistered unregistered
            ? unregistered.detail()
            : "No registry identifies source " + envelope.getSourceId()
        );
    if (
      context.analyzer() != null &&
      !org.itech.ahb.connection.AnalyzerInboundTransport.matches(
        envelope.getProtocol(),
        envelope.getTransport(),
        context.analyzer()
      )
    ) {
      context = new ReceivedInterpretation(
        1,
        null,
        FailureReason.CONNECTION_TRANSPORT_MISMATCH,
        "Received protocol/transport does not match the saved connection"
      );
    }
    try {
      return JSON.writeValueAsString(context);
    } catch (Exception exception) {
      throw new IllegalStateException("Cannot retain receipt interpretation", exception);
    }
  }

  public static ReceivedInterpretation read(String json) {
    if (json == null) return new ReceivedInterpretation(
      1,
      null,
      FailureReason.UNPINNED_PROFILE,
      "Receipt predates retained decoding context; review the connection and explicitly retry unresolved raw input"
    );
    try {
      return JSON.readValue(json, ReceivedInterpretation.class);
    } catch (Exception exception) {
      throw new IllegalArgumentException("Invalid retained receipt interpretation", exception);
    }
  }
}
