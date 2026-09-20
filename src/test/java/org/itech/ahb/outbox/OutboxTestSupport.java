package org.itech.ahb.outbox;

import java.nio.file.Path;
import org.itech.ahb.config.properties.HTTPForwardServerConfigurationProperties;
import org.itech.ahb.connection.AnalyzerRuntimeRegistry;
import org.itech.ahb.normalizer.MessageEnvelope;
import org.itech.ahb.routing.HttpForwardingRouter;
import org.itech.ahb.routing.NormalizedBundleRenderer;

/**
 * Builds the real receive-render-deliver pipeline over a temp-directory outbox.
 *
 * <p>Real store, real dispatcher, real delivery client: the point of these tests is that a result
 * survives failure, and a mocked persistence boundary would let a change that quietly stops
 * persisting still pass.
 */
public final class OutboxTestSupport implements AutoCloseable {

  public final SqliteOutboxStore store;
  public final OutboxProperties properties;
  public final OutboxDispatcher dispatcher;
  public final HttpForwardingRouter router;

  private OutboxTestSupport(
    SqliteOutboxStore store,
    OutboxProperties properties,
    OutboxDispatcher dispatcher,
    HttpForwardingRouter router
  ) {
    this.store = store;
    this.properties = properties;
    this.dispatcher = dispatcher;
    this.router = router;
  }

  /** Same pipeline, on a throwaway directory, for tests that do not already have a temp dir. */
  public static OutboxTestSupport createTemp(
    HTTPForwardServerConfigurationProperties httpConfig,
    AnalyzerRuntimeRegistry registry
  ) {
    try {
      return create(java.nio.file.Files.createTempDirectory("bridge-outbox-test"), httpConfig, registry);
    } catch (java.io.IOException e) {
      throw new IllegalStateException("Could not create a temp directory for the test outbox", e);
    }
  }

  /** A normalizer wired to this pipeline, for tests that exercise receipt through delivery. */
  public org.itech.ahb.normalizer.MessageNormalizer normalizer(
    org.itech.ahb.normalizer.AnalyzerIdentifier identifier,
    AnalyzerRuntimeRegistry registry
  ) {
    return new org.itech.ahb.normalizer.MessageNormalizer(router, identifier, store, registry, null);
  }

  public static OutboxTestSupport create(
    Path directory,
    HTTPForwardServerConfigurationProperties httpConfig,
    AnalyzerRuntimeRegistry registry
  ) {
    SqliteOutboxStore store = new SqliteOutboxStore(directory.resolve("outbox.db"));
    OutboxProperties properties = new OutboxProperties();
    // Tests drive the dispatcher directly, so no jitter and no waiting between attempts.
    properties.getRetry().setJitter(0.0);
    properties.getRetry().setBaseDelay(java.time.Duration.ofMillis(1));
    properties.getRetry().setMaxDelay(java.time.Duration.ofMillis(1));
    FhirDeliveryClient client = new FhirDeliveryClient(httpConfig);
    NormalizedBundleRenderer renderer = new NormalizedBundleRenderer(registry);
    OutboxDispatcher dispatcher = new OutboxDispatcher(store, client, renderer, properties);
    HttpForwardingRouter router = new HttpForwardingRouter(store, dispatcher, client, renderer);
    return new OutboxTestSupport(store, properties, dispatcher, router);
  }

  /**
   * Run the dispatcher on its own thread, as production does, for tests that exercise a transport
   * end to end and wait for OpenELIS to see the delivery.
   */
  public OutboxTestSupport startDispatcher() {
    dispatcher.start();
    return this;
  }

  /**
   * Persist the message and render it, exactly as the normalizer does, without delivering.
   *
   * @return what the transport would report back to the analyzer
   */
  public boolean receive(MessageEnvelope envelope) {
    if (envelope.getRawMessage() == null || envelope.getRawMessage().isBlank()) {
      // The normalizer refuses a blank message before the store sees it; mirror that here so the
      // harness exercises the same path production does.
      return false;
    }
    Receipt receipt = store.receive(
      new ReceivedMessage(
        envelope.getSourceId(),
        envelope.getSourcePort(),
        envelope.getProtocol(),
        envelope.getTransport(),
        envelope.getProtocolAnalyzerHint(),
        envelope.getRawMessage(),
        null,
        envelope.getReceivedAt()
      )
    );
    return router.route(withReceipt(envelope, receipt.id()));
  }

  /** Receive, then run the dispatcher until nothing else is due. */
  public boolean receiveAndDeliver(MessageEnvelope envelope) {
    boolean accepted = receive(envelope);
    dispatcher.dispatchDue();
    return accepted;
  }

  private static MessageEnvelope withReceipt(MessageEnvelope envelope, String receiptId) {
    return MessageEnvelope.builder()
      .protocol(envelope.getProtocol())
      .transport(envelope.getTransport())
      .sourceId(envelope.getSourceId())
      .sourcePort(envelope.getSourcePort())
      .rawMessage(envelope.getRawMessage())
      .receivedAt(envelope.getReceivedAt())
      .protocolAnalyzerHint(envelope.getProtocolAnalyzerHint())
      .resolvedAnalyzerId(envelope.getResolvedAnalyzerId())
      .outboxReceiptId(receiptId)
      .build();
  }

  @Override
  public void close() {
    dispatcher.stop();
    store.close();
  }
}
