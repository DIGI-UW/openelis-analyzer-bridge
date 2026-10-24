package org.itech.ahb.normalizer;

import io.micrometer.core.instrument.Timer;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.itech.ahb.connection.AnalyzerInboundTransport;
import org.itech.ahb.connection.AnalyzerRuntimeRegistry;
import org.itech.ahb.metrics.MetricsService;
import org.itech.ahb.model.Protocol;
import org.itech.ahb.model.Transport;
import org.itech.ahb.outbox.FailureReason;
import org.itech.ahb.outbox.OutboxStore;
import org.itech.ahb.outbox.Receipt;
import org.itech.ahb.outbox.ReceivedMessage;
import org.itech.ahb.routing.HttpForwardingRouter;
import org.itech.ahb.routing.MessageRouter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Central message orchestration service for the Universal Analyzer Bridge.
 * <p>
 * MessageNormalizer implements the {@link MessageRouter} interface and serves as
 * the @Primary bean, ensuring that all message routing in the application flows
 * through this component. This design allows existing MLLP code (which depends on
 * MessageRouter) to automatically use the normalizer without any code changes.
 * </p>
 * <p>
 * The normalizer orchestrates:
 * <ul>
 *   <li>Analyzer identification via {@link AnalyzerIdentifier} (source binding)</li>
 *   <li>Message enrichment with canonical analyzer ID and protocol hint metadata</li>
 *   <li>Routing to OpenELIS via {@link HttpForwardingRouter}</li>
 *   <li>Audit logging of all message flows</li>
 * </ul>
 * </p>
 * <p>
 * All transport listeners (MLLP, Serial, File, HTTP Input, ASTM TCP) delegate to
 * this service after creating a {@link MessageEnvelope} with transport metadata.
 * </p>
 *
 * @see MessageRouter
 * @see HttpForwardingRouter
 * @see AnalyzerIdentifier
 * @see MessageEnvelope
 */
@Component
@Primary // CRITICAL: Makes this the default MessageRouter bean
@Slf4j
public class MessageNormalizer implements MessageRouter {

  private final HttpForwardingRouter forwardingRouter; // Inject by CONCRETE TYPE
  private final AnalyzerRuntimeRegistry registry;
  private final MetricsService metricsService; // nullable — optional dependency

  /**
   * Durable store for every received message. Deliberately required rather than optional: the
   * store this replaced was optional and gated on the FILE transport being enabled, so switching
   * off the file watcher silently switched off failure tracking for every transport. A store whose
   * whole purpose is that nothing received is lost cannot be something a deployment turns off by
   * accident.
   */
  private final OutboxStore outbox;

  @Autowired
  public MessageNormalizer(
    HttpForwardingRouter forwardingRouter,
    OutboxStore outbox,
    @Autowired(required = false) AnalyzerRuntimeRegistry registry,
    @Autowired(required = false) MetricsService metricsService
  ) {
    this.forwardingRouter = forwardingRouter;
    this.outbox = outbox;
    this.registry = registry;
    this.metricsService = metricsService;
  }

  /** Protocol listeners use this before ACK; no identity or rendering failure can lose the receipt. */
  public void persistBeforeAcknowledgement(MessageEnvelope envelope) {
    outbox.receive(
      new ReceivedMessage(
        envelope.getSourceId(),
        envelope.getSourcePort(),
        envelope.getProtocol(),
        envelope.getTransport(),
        envelope.getProtocolAnalyzerHint(),
        envelope.getRawMessage(),
        null,
        envelope.getReceivedAt(),
        envelope.getListenerPort(),
        org.itech.ahb.routing.ReceivedInterpretation.capture(registry, envelope)
      )
    );
  }

  public org.itech.ahb.lib.astm.communication.AstmReceiptObserver astmReceipt(MessageEnvelope source) {
    return new org.itech.ahb.outbox.DurableAstmReceipt(
      outbox,
      new ReceivedMessage(
        source.getSourceId(),
        source.getSourcePort(),
        Protocol.ASTM,
        source.getTransport(),
        source.getProtocolAnalyzerHint(),
        null,
        null,
        source.getReceivedAt(),
        source.getListenerPort()
      ),
      received ->
        received.withInterpretation(
          org.itech.ahb.routing.ReceivedInterpretation.capture(
            registry,
            MessageEnvelope.builder()
              .protocol(received.protocol())
              .transport(received.transport())
              .sourceId(received.sourceId())
              .sourcePort(received.sourcePort() == null ? 0 : received.sourcePort())
              .protocolAnalyzerHint(received.protocolHint())
              .rawMessage(received.rawText())
              .listenerPort(received.listenerPort())
              .build()
          )
        )
    );
  }

  /**
   * MessageRouter.route() implementation — allows MLLP to use this transparently.
   * <p>
   * This method enables existing code that depends on {@link MessageRouter} to
   * automatically use the normalizer via Spring's @Primary bean selection.
   * </p>
   * <p>
   * Delegates to {@link #process(MessageEnvelope)} for the actual implementation.
   * </p>
   *
   * @param envelope the message with transport metadata
   * @return true if routing succeeded, false otherwise
   */
  @Override
  public boolean route(MessageEnvelope envelope) {
    return process(envelope);
  }

  /**
   * Process a message envelope: resolve analyzer identity, enrich envelope, route to OpenELIS.
   * <p>
   * Called directly by Serial/File/HTTP/ASTM handlers, or via {@link #route(MessageEnvelope)}
   * by MLLP. This method:
   * <ol>
   *   <li>Resolves canonical analyzer ID from source binding</li>
   *   <li>Validates protocol hint consistency (if provided)</li>
   *   <li>Enriches the envelope with canonical resolved analyzer metadata</li>
   *   <li>Logs the routing operation for audit purposes</li>
   *   <li>Routes to OpenELIS via {@link HttpForwardingRouter}</li>
   * </ol>
   * </p>
   *
   * @param envelope the message with transport metadata
   * @return true if routing succeeded, false otherwise
   */
  public boolean process(MessageEnvelope envelope) {
    if (envelope == null) {
      log.error("Cannot process null MessageEnvelope");
      return false;
    }
    if (envelope.getProtocol() == null || envelope.getTransport() == null) {
      log.error(
        "MessageEnvelope missing protocol or transport: protocol={}, transport={}, sourceId={}, analyzerId={}",
        envelope.getProtocol(),
        envelope.getTransport(),
        envelope.getSourceId(),
        envelope.getResolvedAnalyzerId()
      );
      return false;
    }
    if (envelope.getSourceId() == null || envelope.getSourceId().trim().isEmpty()) {
      log.error("MessageEnvelope missing sourceId for {}", envelope.getProtocol());
      return false;
    }
    if (envelope.getRawMessage() == null || envelope.getRawMessage().trim().isEmpty()) {
      log.error("MessageEnvelope missing rawMessage for {}", envelope.getProtocol());
      return false;
    }

    String protocol = envelope.getProtocol().name();
    String transport = envelope.getTransport().name();

    // Start timing
    Timer.Sample sample = metricsService != null ? metricsService.startRouting() : null;

    // ASTM queries carry no results. Persisting them would fill the dead-message queue with
    // traffic that was never deliverable, so they are recognized before anything is stored and
    // handled where they always were, below.
    boolean queryOnly = envelope.getProtocol() == Protocol.ASTM && isQueryOnlyAstmMessage(envelope.getRawMessage());

    // Persist before anything else can fail. From here the message is recoverable even if
    // identity resolution, parsing or OpenELIS itself is what goes wrong.
    Receipt receipt = null;
    if (!queryOnly) {
      try {
        receipt = outbox.receive(
          new ReceivedMessage(
            envelope.getSourceId(),
            envelope.getSourcePort(),
            envelope.getProtocol(),
            envelope.getTransport(),
            envelope.getProtocolAnalyzerHint(),
            envelope.getRawMessage(),
            null,
            envelope.getReceivedAt(),
            envelope.getListenerPort(),
            org.itech.ahb.routing.ReceivedInterpretation.capture(registry, envelope)
          )
        );
      } catch (RuntimeException e) {
        // Nothing was stored, so the transport must refuse the message rather than imply the
        // bridge has it. For analyzers that resend on a negative acknowledgement this is the
        // one response that can still save the result.
        log.error(
          "Could not persist a received {} message from {}; refusing it so it is not lost silently",
          protocol,
          envelope.getSourceId(),
          e
        );
        if (metricsService != null) metricsService.recordRouted(sample, protocol, transport, false);
        return false;
      }
      if (receipt.alreadyPresent()) {
        log.info(
          "Message from {} is identical to one already held in the outbox ({}); not storing it twice",
          envelope.getSourceId(),
          receipt.id()
        );
      }
    }

    if (!queryOnly) {
      var context = org.itech.ahb.routing.ReceivedInterpretation.read(
        outbox.interpretationContext(receipt.id()).orElse(null)
      );
      if (context.analyzer() == null) {
        outbox.markDeadLettered(receipt.id(), context.reason(), context.detail());
        recordIdentity(
          protocol,
          transport,
          context.reason() == FailureReason.AMBIGUOUS_SOURCE
            ? "ambiguous_source"
            : context.reason() == FailureReason.CONNECTION_TRANSPORT_MISMATCH
              ? "transport_mismatch"
              : "unregistered_source"
        );
        if (metricsService != null) metricsService.recordRouted(sample, protocol, transport, false);
        return false;
      }
      if (envelope.getProtocolAnalyzerHint() != null && !envelope.getProtocolAnalyzerHint().isBlank()) {
        recordIdentity(
          protocol,
          transport,
          protocolHintMatchesRegistration(envelope.getProtocolAnalyzerHint(), context.analyzer())
            ? "corroborated"
            : "mismatch"
        );
      }
      MessageEnvelope received = MessageEnvelope.builder()
        .protocol(envelope.getProtocol())
        .transport(envelope.getTransport())
        .sourceId(envelope.getSourceId())
        .sourcePort(envelope.getSourcePort())
        .rawMessage(envelope.getRawMessage())
        .receivedAt(envelope.getReceivedAt())
        .protocolAnalyzerHint(envelope.getProtocolAnalyzerHint())
        .listenerPort(envelope.getListenerPort())
        .outboxReceiptId(receipt.id())
        .resolvedAnalyzerId(context.analyzer().getId())
        .build();
      boolean success = forwardingRouter.route(received);
      if (metricsService != null) {
        metricsService.recordReceived(protocol, transport);
        metricsService.recordRouted(sample, protocol, transport, success);
      }
      return success;
    }

    // Record message received
    if (metricsService != null) {
      metricsService.recordReceived(protocol, transport);
    }

    String protocolHint = envelope.getProtocolAnalyzerHint();

    AnalyzerRuntimeRegistry.Resolution resolution = registry != null
      ? registry.resolve(envelope.getListenerPort(), envelope.getSourceId(), protocolHint)
      : null;
    AnalyzerRuntimeRegistry.AnalyzerEntry registryEntry = resolution instanceof
      AnalyzerRuntimeRegistry.Resolution.Resolved resolved
      ? resolved.entry()
      : null;
    if (resolution instanceof AnalyzerRuntimeRegistry.Resolution.Ambiguous ambiguous) {
      recordIdentity(protocol, transport, "ambiguous_source");
      log.warn("Holding message from '{}' in the dead-message queue: {}", envelope.getSourceId(), ambiguous.detail());
      if (receipt != null) {
        outbox.markDeadLettered(receipt.id(), FailureReason.AMBIGUOUS_SOURCE, ambiguous.detail());
      }
      if (metricsService != null) metricsService.recordRouted(sample, protocol, transport, false);
      return false;
    }
    if (
      registry != null &&
      !AnalyzerInboundTransport.matches(envelope.getProtocol(), envelope.getTransport(), registryEntry)
    ) {
      recordIdentity(protocol, transport, registryEntry == null ? "unregistered_source" : "transport_mismatch");
      log.warn(
        "Rejecting protocol/transport inconsistent with saved connection for source '{}'",
        envelope.getSourceId()
      );
      if (receipt != null) {
        outbox.markDeadLettered(
          receipt.id(),
          registryEntry == null ? FailureReason.UNREGISTERED_SOURCE : FailureReason.CONNECTION_TRANSPORT_MISMATCH,
          registryEntry == null
            ? ((AnalyzerRuntimeRegistry.Resolution.Unregistered) resolution).detail()
            : "Source " + envelope.getSourceId() + " sent over a transport its saved connection does not accept"
        );
      }
      if (metricsService != null) metricsService.recordRouted(sample, protocol, transport, false);
      return false;
    }

    // Queries do not produce results. Source/transport validation above still applies.
    if (metricsService != null) metricsService.recordRouted(sample, protocol, transport, true);
    return true;
  }

  /**
   * Detect ASTM Q-only messages (queries with no R-records).
   *
   * <p>Bidirectional ASTM analyzers (GeneXpert, etc.) periodically send
   * Q-records asking the LIS for pending orders. These messages carry no
   * results — they're requests, not responses. Trying to parse them as
   * results produces "FHIR parse produced no results" errors that
   * cluttered telemetry until this filter was added (see plan
   * .claude/plans/abundant-chasing-hoare.md Issue #3).
   *
   * <p>Until OGC-335/336 implements the bidirectional order-response
   * handler, the right thing to do is detect Q-only messages early,
   * log INFO, and ack as success (we received and intentionally
   * declined to forward).
   *
   * @param rawMessage the ASTM message payload
   * @return true if the message contains a Q-record but no R-record
   */
  public static boolean isQueryOnlyAstmMessage(String rawMessage) {
    if (rawMessage == null || rawMessage.isBlank()) return false;
    // The header record declares the field delimiter as its second character.
    String header = rawMessage.stripLeading();
    char delimiter = header.length() > 1 && header.charAt(0) == 'H' ? header.charAt(1) : '|';
    boolean hasQ = false;
    boolean hasR = false;
    for (String segment : rawMessage.split("\r")) {
      String trimmed = segment.trim();
      if (trimmed.length() > 1 && trimmed.charAt(1) == delimiter) {
        if (trimmed.charAt(0) == 'Q') hasQ = true;
        if (trimmed.charAt(0) == 'R') hasR = true;
      }
    }
    return hasQ && !hasR;
  }

  private void recordIdentity(String protocol, String transport, String mode) {
    if (metricsService != null) {
      metricsService.recordIdentityMismatch(protocol, transport, mode);
    }
  }

  private boolean protocolHintMatchesRegistration(
    String protocolHint,
    AnalyzerRuntimeRegistry.AnalyzerEntry registryEntry
  ) {
    if (protocolHint == null || protocolHint.isBlank() || registryEntry == null) {
      return false;
    }

    // The pinned profile's identifier pattern is the only content signal that
    // can corroborate the source-bound connection.
    Pattern idPattern = registryEntry.getCompiledIdentifierPattern();
    return idPattern != null && idPattern.matcher(protocolHint).find();
  }
}
