package org.itech.ahb.routing;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.itech.ahb.connection.AnalyzerRuntimeRegistry;
import org.itech.ahb.fhir.ASTMResultParser;
import org.itech.ahb.fhir.FhirBundleBuilder;
import org.itech.ahb.fhir.FileResultParser;
import org.itech.ahb.fhir.HL7ResultParser;
import org.itech.ahb.model.Protocol;
import org.itech.ahb.model.Transport;
import org.itech.ahb.normalizer.MessageEnvelope;
import org.itech.ahb.outbox.DeliveryIdentity;
import org.itech.ahb.outbox.FailureReason;
import org.itech.ahb.outbox.RenderedDelivery;
import org.itech.ahb.profile.ControlResultRecognition;
import org.springframework.stereotype.Component;

/**
 * Turns a received message into the deliverable units OpenELIS accepts: one normalized FHIR bundle
 * per accession, each carrying a delivery identity derived from the received content.
 *
 * <p>Separated from the forwarding router so that rendering is a pure function of the message and
 * the analyzer's pinned profile, with no network I/O. That is what lets the bridge persist a
 * received result before it ever tries to deliver it, and re-render one later against corrected
 * configuration without re-receiving it.
 */
@Component
@Slf4j
public class NormalizedBundleRenderer {

  private final AnalyzerRuntimeRegistry registry;

  public NormalizedBundleRenderer(AnalyzerRuntimeRegistry registry) {
    this.registry = registry;
  }

  /** What rendering produced: deliverable units, or a reason nothing can be delivered. */
  public sealed interface Outcome {
    /** One or more bundles ready to POST. */
    record Rendered(List<RenderedDelivery> deliveries) implements Outcome {}

    /**
     * Nothing can be delivered from this message as configuration currently stands.
     *
     * @param reason what an operator has to fix
     * @param message the detail to show them and to log
     */
    record Failed(FailureReason reason, String message) implements Outcome {}
  }

  /**
   * Render every deliverable unit in the message.
   *
   * @param targetUri the OpenELIS endpoint the deliveries will be POSTed to, recorded with each
   *     delivery so an operator can see where a stored delivery was aimed
   */
  public Outcome render(MessageEnvelope envelope, String targetUri) {
    // The retained message is authoritative when re-rendering; stored hints remain audit evidence.
    String senderHint = envelope.getProtocol() == Protocol.HL7
      ? HL7ResultParser.sendingApplication(envelope.getRawMessage())
      : envelope.getProtocolAnalyzerHint();
    AnalyzerRuntimeRegistry.Resolution resolution = registry == null || envelope.getSourceId() == null
      ? null
      : registry.resolve(envelope.getListenerPort(), envelope.getSourceId(), senderHint);
    if (resolution instanceof AnalyzerRuntimeRegistry.Resolution.Ambiguous ambiguous) {
      return new Outcome.Failed(FailureReason.AMBIGUOUS_SOURCE, ambiguous.detail());
    }
    Optional<AnalyzerRuntimeRegistry.AnalyzerEntry> registered = resolution instanceof
      AnalyzerRuntimeRegistry.Resolution.Resolved resolved
      ? Optional.of(resolved.entry())
      : Optional.empty();
    if (registered.isEmpty()) {
      return new Outcome.Failed(
        FailureReason.UNREGISTERED_SOURCE,
        "FHIR routing requires control-result recognition from a pinned profile"
      );
    }
    AnalyzerRuntimeRegistry.AnalyzerEntry analyzer = registered.get();
    if (
      !org.itech.ahb.connection.AnalyzerInboundTransport.matches(
        envelope.getProtocol(),
        envelope.getTransport(),
        analyzer
      )
    ) {
      return new Outcome.Failed(
        FailureReason.CONNECTION_TRANSPORT_MISMATCH,
        "Received " +
        envelope.getProtocol() +
        "/" +
        envelope.getTransport() +
        " does not match saved " +
        analyzer.getExpectedProtocol() +
        "/" +
        analyzer.getInboundTransport()
      );
    }
    if (analyzer.getControlResultRecognition() == null) {
      return new Outcome.Failed(
        FailureReason.UNPINNED_PROFILE,
        "FHIR routing requires control-result recognition from a pinned profile"
      );
    }
    if (envelope.getProtocol() == Protocol.ASTM && analyzer.getAstmResultRecordSelection() == null) {
      return new Outcome.Failed(
        FailureReason.UNPINNED_PROFILE,
        "FHIR routing requires ASTM result-record selection from a pinned profile"
      );
    }

    return envelope.getProtocol() == Protocol.CSV
      ? renderTabular(envelope, analyzer, targetUri)
      : renderSocket(envelope, analyzer, targetUri);
  }

  private Outcome renderSocket(
    MessageEnvelope envelope,
    AnalyzerRuntimeRegistry.AnalyzerEntry analyzer,
    String targetUri
  ) {
    ControlResultRecognition recognition = analyzer.getControlResultRecognition();
    HL7ResultParser.ParsedResults parsed =
      switch (envelope.getProtocol()) {
        case HL7 -> HL7ResultParser.parse(splitOn(envelope.getRawMessage(), true), recognition);
        case ASTM -> ASTMResultParser.parse(
          splitOn(envelope.getRawMessage(), false),
          recognition,
          analyzer.getAstmResultRecordSelection()
        );
        default -> null;
      };
    if (parsed == null || parsed.results().isEmpty()) {
      return new Outcome.Failed(
        FailureReason.PARSE_NO_RESULTS,
        envelope.getProtocol() + " parsing produced no results"
      );
    }
    String messageId = DeliveryIdentity.forAccession(
      envelope.getProtocol(),
      analyzer.getBridgeConnectionId(),
      DeliveryIdentity.contentHash(envelope.getRawMessage()),
      parsed.accessionNumber()
    );
    return renderOne(envelope, analyzer, parsed, messageId, targetUri);
  }

  private Outcome renderTabular(
    MessageEnvelope envelope,
    AnalyzerRuntimeRegistry.AnalyzerEntry analyzer,
    String targetUri
  ) {
    if (
      envelope.getTransport() != Transport.HTTP ||
      !"HTTP".equals(analyzer.getInboundTransport()) ||
      !"FILE".equals(analyzer.getExpectedProtocol()) ||
      !("CSV".equals(analyzer.getFileFormat()) || "TSV".equals(analyzer.getFileFormat())) ||
      analyzer.getTabularResultValueSelection() == null
    ) {
      return new Outcome.Failed(
        FailureReason.UNPINNED_PROFILE,
        "CSV input requires a saved HTTP connection with a pinned tabular profile"
      );
    }
    byte[] content = envelope.getRawMessage().getBytes(StandardCharsets.UTF_8);
    List<HL7ResultParser.ParsedResults> accessions = FileResultParser.parseCsv(
      content,
      analyzer.getColumnMappings(),
      analyzer.getDelimiter(),
      analyzer.getSkipRows(),
      analyzer.getFileTestCode(),
      analyzer.getControlResultRecognition(),
      analyzer.getTabularFileLayout(),
      analyzer.getTabularResultValueSelection()
    );
    if (accessions == null || accessions.isEmpty()) {
      return new Outcome.Failed(FailureReason.PARSE_NO_RESULTS, "CSV input produced no results");
    }
    String hash = DeliveryIdentity.contentHash(content);
    List<RenderedDelivery> deliveries = new ArrayList<>();
    for (HL7ResultParser.ParsedResults parsed : accessions) {
      String messageId = DeliveryIdentity.forAccession(
        envelope.getProtocol(),
        analyzer.getBridgeConnectionId(),
        hash,
        parsed.accessionNumber()
      );
      Outcome one = renderOne(envelope, analyzer, parsed, messageId, targetUri);
      if (one instanceof Outcome.Failed failed) {
        return failed;
      }
      deliveries.addAll(((Outcome.Rendered) one).deliveries());
    }
    return new Outcome.Rendered(deliveries);
  }

  private Outcome renderOne(
    MessageEnvelope envelope,
    AnalyzerRuntimeRegistry.AnalyzerEntry analyzer,
    HL7ResultParser.ParsedResults parsed,
    String messageId,
    String targetUri
  ) {
    FhirBundleBuilder.DeviceInfo deviceInfo = FhirBundleBuilder.DeviceInfo.fromSenderToken(
      envelope.getSourceId(),
      envelope.getProtocolAnalyzerHint()
    );
    FhirBundleBuilder.AnalyzerContext analyzerContext = new FhirBundleBuilder.AnalyzerContext(
      analyzer.getBridgeConnectionId(),
      analyzer.getId(),
      analyzer.getProfileId(),
      analyzer.getProfileRevision(),
      envelope.getProtocol().name(),
      envelope.getTransport().name(),
      deviceInfo,
      analyzer.getControlResultRecognition(),
      analyzer.getRecognitionFingerprint()
    );
    try {
      String fhirJson = FhirBundleBuilder.buildNormalizedBundle(
        parsed.accessionNumber(),
        parsed.results(),
        analyzerContext,
        analyzer::getLoincForCode,
        messageId
      );
      return new Outcome.Rendered(
        List.of(
          new RenderedDelivery(
            messageId,
            parsed.accessionNumber(),
            fhirJson,
            analyzer.getBridgeConnectionId(),
            analyzer.getId(),
            analyzer.getProfileId(),
            analyzer.getProfileRevision(),
            targetUri
          )
        )
      );
    } catch (RuntimeException e) {
      log.error("Failed to render the OpenELIS contract for accession {}", parsed.accessionNumber(), e);
      return new Outcome.Failed(
        FailureReason.RENDER_ERROR,
        "Could not render the OpenELIS contract: " + e.getMessage()
      );
    }
  }

  /** Split a raw message into non-blank lines, normalizing line endings for HL7. */
  private static List<String> splitOn(String raw, boolean normalizeNewlines) {
    if (raw == null || raw.isBlank()) {
      return List.of();
    }
    String source = normalizeNewlines ? raw.replace("\r\n", "\r").replace("\n", "\r") : raw;
    List<String> parts = new ArrayList<>();
    for (String part : source.split(normalizeNewlines ? "\r" : "[\\r\\n]+")) {
      if (!part.isBlank()) {
        parts.add(part);
      }
    }
    return parts;
  }
}
