package org.itech.ahb.routing;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.itech.ahb.normalizer.MessageEnvelope;
import org.itech.ahb.outbox.FhirDeliveryClient;
import org.itech.ahb.outbox.OutboxDispatcher;
import org.itech.ahb.outbox.OutboxStore;
import org.itech.ahb.outbox.RenderedDelivery;
import org.springframework.stereotype.Component;

/**
 * Renders registered analyzer traffic into the versioned OpenELIS result contract and hands it to
 * the delivery outbox.
 *
 * <p>Despite the name this class no longer forwards anything itself. It used to POST on the
 * receiving thread and retry three times over about three seconds, after which the message was gone;
 * a DNS failure that brief was enough to lose results in production. Delivery now belongs to
 * {@link OutboxDispatcher}, which works from durable rows and can keep trying across restarts.
 *
 * @see MessageRouter
 * @see org.itech.ahb.normalizer.MessageEnvelope
 */
@Component
@Slf4j
public class HttpForwardingRouter implements MessageRouter {

  private final OutboxStore outbox;
  private final OutboxDispatcher dispatcher;
  private final FhirDeliveryClient deliveryClient;
  private final NormalizedBundleRenderer renderer;

  public HttpForwardingRouter(
    OutboxStore outbox,
    OutboxDispatcher dispatcher,
    FhirDeliveryClient deliveryClient,
    NormalizedBundleRenderer renderer
  ) {
    this.outbox = outbox;
    this.dispatcher = dispatcher;
    this.deliveryClient = deliveryClient;
    this.renderer = renderer;
  }

  /**
   * Accept a message that has already been persisted, render it, and queue it for delivery.
   *
   * @param envelope the message, carrying the outbox receipt written when it was received
   * @return true when the message is durably accounted for, whether that means queued for delivery
   *     or held in the dead-message queue for an operator. False means it was not persisted and the
   *     transport should refuse it so the analyzer can send it again.
   */
  @Override
  public boolean route(MessageEnvelope envelope) {
    if (envelope == null) {
      log.error("Cannot route null MessageEnvelope");
      return false;
    }
    if (envelope.getProtocol() == null || envelope.getTransport() == null) {
      log.error("MessageEnvelope missing protocol or transport");
      return false;
    }
    if (envelope.getSourceId() == null || envelope.getSourceId().trim().isEmpty()) {
      log.error("MessageEnvelope missing sourceId");
      return false;
    }
    if (envelope.getRawMessage() == null || envelope.getRawMessage().trim().isEmpty()) {
      log.error("MessageEnvelope missing rawMessage");
      return false;
    }

    log.debug(
      "Routing {} message from {} via {}",
      envelope.getProtocol(),
      envelope.getSourceId(),
      envelope.getTransport()
    );

    return routeNormalized(envelope);
  }

  /**
   * Route a message as a FHIR R4 transaction Bundle.
   *
   * <p>Parses the raw message using the protocol-specific parser, builds a FHIR
   * Bundle, and POSTs to OE's {@code /analyzer/fhir} endpoint.
   */
  /**
   * Render the message into deliverable bundles and hand them to the outbox.
   *
   * <p>No network I/O happens here any more. The bridge's promise is that a received result survives
   * until OpenELIS accepts it, and a delivery attempt made on the receiving thread cannot keep that
   * promise: the analyzer's session is already over, so a failure has nowhere to go. Delivery is the
   * dispatcher's job, against durable rows, for as long as it takes.
   */
  private boolean routeNormalized(MessageEnvelope envelope) {
    String receiptId = envelope.getOutboxReceiptId();
    if (receiptId == null) {
      log.error(
        "Refusing to route a message that was not persisted first (source {}); this is a wiring error",
        envelope.getSourceId()
      );
      return false;
    }
    NormalizedBundleRenderer.Outcome outcome = renderer.render(envelope, deliveryClient.targetUri().toString());
    if (outcome instanceof NormalizedBundleRenderer.Outcome.Failed failed) {
      log.error("{} for analyzer source {}", failed.message(), envelope.getSourceId());
      outbox.markDeadLettered(receiptId, failed.reason(), failed.message());
      // The message is held with its complete payload, so the transport can report a clean receipt:
      // there is nothing the analyzer could usefully resend.
      return true;
    }
    List<RenderedDelivery> deliveries = ((NormalizedBundleRenderer.Outcome.Rendered) outcome).deliveries();
    outbox.markRendered(receiptId, deliveries);
    dispatcher.signal();
    log.info(
      "Queued {} deliveries from {} message from {} for OpenELIS",
      deliveries.size(),
      envelope.getProtocol(),
      envelope.getSourceId()
    );
    return true;
  }
}
