package org.itech.ahb.outbox;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.itech.ahb.normalizer.MessageEnvelope;
import org.itech.ahb.routing.NormalizedBundleRenderer;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Delivers outbox entries to OpenELIS, on its own thread, until each one is accepted or can only be
 * resolved by a person.
 *
 * <p>Deliberately one worker. Deliveries from an analyzer are ordered, retries during an outage
 * would otherwise arrive as a burst against a recovering OpenELIS, and a single worker makes the
 * lease bookkeeping something a reader can follow.
 */
@Component
@Slf4j
public class OutboxDispatcher {

  private final OutboxStore store;
  private final FhirDeliveryClient client;
  private final NormalizedBundleRenderer renderer;
  private final OutboxProperties properties;
  private final BackoffPolicy backoff;
  private final String owner;

  /** Housekeeping cadence. Retention is measured in days, so hourly is frequent enough. */
  private static final Duration PURGE_INTERVAL = Duration.ofHours(1);

  /** Wake-ups from the receive path and the retry endpoint, so a new delivery does not wait for a poll. */
  private final BlockingQueue<Object> signals = new LinkedBlockingQueue<>(1);

  private volatile boolean running;
  private volatile boolean stopping;
  private volatile String inFlightId;
  private volatile Instant lastPollAt;
  private volatile Instant lastPurgeAt;
  private Thread worker;

  public OutboxDispatcher(
    OutboxStore store,
    FhirDeliveryClient client,
    NormalizedBundleRenderer renderer,
    OutboxProperties properties
  ) {
    this.store = store;
    this.client = client;
    this.renderer = renderer;
    this.properties = properties;
    this.backoff = BackoffPolicy.from(properties.getRetry());
    this.owner = "dispatcher-" + ProcessHandle.current().pid();
  }

  /**
   * Start after the context is up, so saved analyzer connections have been restored and a delivery
   * recovered from a previous run is rendered against the configuration it was pinned to.
   */
  @EventListener(ApplicationReadyEvent.class)
  public synchronized void start() {
    if (running) {
      return;
    }
    store.recoverInterrupted(Instant.now());
    running = true;
    stopping = false;
    worker = new Thread(this::runLoop, "outbox-dispatcher");
    worker.setDaemon(true);
    worker.start();
    log.info(
      "Outbox dispatcher started: maxAttempts={}, baseDelay={}, maxDelay={}",
      properties.getRetry().getMaxAttempts(),
      properties.getRetry().getBaseDelay(),
      properties.getRetry().getMaxDelay()
    );
  }

  /** Nudge the dispatcher because new work exists. Never blocks the caller. */
  public void signal() {
    signals.offer(Boolean.TRUE);
  }

  @PreDestroy
  public synchronized void stop() {
    if (!running) {
      return;
    }
    stopping = true;
    signal();
    Thread current = worker;
    if (current != null) {
      try {
        // Long enough for an in-flight attempt to finish rather than be abandoned mid-POST.
        current.join(Duration.ofSeconds(properties.getLease().toSeconds()).toMillis());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      if (current.isAlive()) {
        current.interrupt();
      }
    }
    running = false;
    log.info("Outbox dispatcher stopped");
  }

  private void runLoop() {
    while (!stopping) {
      try {
        purgeIfDue();
        int delivered = dispatchDue();
        if (delivered == 0) {
          signals.poll(properties.getPollInterval().toMillis(), TimeUnit.MILLISECONDS);
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      } catch (RuntimeException e) {
        // A failure here must never end the loop: the entries are still durable, and a dispatcher
        // that died silently would look exactly like an OpenELIS outage.
        log.error("Outbox dispatch cycle failed; retrying after the poll interval", e);
        try {
          Thread.sleep(properties.getPollInterval().toMillis());
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          return;
        }
      }
    }
  }

  /**
   * Process every entry currently due, on the calling thread.
   *
   * @return how many entries were attempted
   */
  public int dispatchDue() {
    int handled = 0;
    Set<String> attempted = new HashSet<>();
    while (!stopping) {
      Instant now = Instant.now();
      lastPollAt = now;
      Optional<OutboxEntry> claimed = store.claimNextDue(now, properties.getLease(), owner);
      if (claimed.isEmpty()) {
        return handled;
      }
      OutboxEntry entry = claimed.get();
      if (!attempted.add(entry.id())) {
        // Its next attempt already came due inside this pass. Leave it for the next one rather than
        // spinning on a single entry while everything behind it waits.
        store.releaseLease(entry.id());
        return handled;
      }
      inFlightId = entry.id();
      try {
        attempt(entry);
      } finally {
        inFlightId = null;
      }
      handled++;
    }
    return handled;
  }

  /**
   * Age out terminal entries, hourly.
   *
   * <p>Only delivered entries and dead letters an operator has dismissed. Anything still undelivered
   * stays, however old: the point of this store is that a result the laboratory produced is still
   * here when someone comes looking for it.
   */
  private void purgeIfDue() {
    Instant now = Instant.now();
    if (lastPurgeAt != null && lastPurgeAt.plus(PURGE_INTERVAL).isAfter(now)) {
      return;
    }
    lastPurgeAt = now;
    try {
      int removed = store.purgeExpired(
        now,
        properties.getRetention().getDelivered(),
        properties.getRetention().getDismissed()
      );
      if (removed > 0) {
        log.info(
          "Purged {} terminal outbox entries past retention (delivered after {}, dismissed after {})",
          removed,
          properties.getRetention().getDelivered(),
          properties.getRetention().getDismissed()
        );
      }
    } catch (RuntimeException e) {
      // Retention housekeeping must never stop deliveries.
      log.warn("Outbox retention purge failed; it will be retried on the next cycle: {}", e.getMessage());
    }
  }

  private void attempt(OutboxEntry entry) {
    if (entry.state() == OutboxState.RECEIVED) {
      // Persisted but never rendered: the bridge stopped between receiving and rendering. The
      // message is intact, so render it now rather than asking the analyzer to send it again.
      renderRecovered(entry);
      return;
    }
    Optional<String> payload = store.fhirPayload(entry.id());
    if (payload.isEmpty()) {
      log.error("Outbox entry {} is {} but holds no rendered payload; dead-lettering it", entry.id(), entry.state());
      store.markDeadLettered(entry.id(), FailureReason.RENDER_ERROR, "Entry has no rendered payload to deliver");
      return;
    }

    Instant startedAt = Instant.now();
    DeliveryOutcome outcome = client.deliver(payload.get());
    Instant finishedAt = Instant.now();

    if (Thread.currentThread().isInterrupted() && !outcome.reachedOpenElis()) {
      // Shutting down mid-attempt. The outcome is unknown, so leave the entry exactly as it was and
      // let the next run redeliver it; OpenELIS deduplicates the repeat.
      store.releaseLease(entry.id());
      log.info("Released outbox entry {} without counting an attempt: bridge is shutting down", entry.id());
      return;
    }

    DeliveryDecision decision = DeliveryOutcomeClassifier.classify(outcome);
    String excerpt = outcome.body();
    String error = outcome.describeFailure();

    switch (decision.disposition()) {
      case DELIVERED -> {
        store.markDelivered(entry.id(), outcome.httpStatus(), oeReceiptOf(outcome.body()), excerpt);
        store.recordAttempt(
          entry.id(),
          OutboxAttempt.Kind.AUTO,
          null,
          startedAt,
          finishedAt,
          "DELIVERED",
          outcome.httpStatus(),
          null,
          excerpt
        );
        log.info(
          "Delivered outbox entry {} (accession {}) to OpenELIS on attempt {}",
          entry.id(),
          entry.accession(),
          entry.attempts() + 1
        );
      }
      case RETRY -> {
        int attemptsAfter = entry.attempts() + 1;
        if (attemptsAfter >= properties.getRetry().getMaxAttempts()) {
          store.markRejected(entry.id(), FailureReason.RETRY_EXHAUSTED, outcome.httpStatus(), error, excerpt);
          store.recordAttempt(
            entry.id(),
            OutboxAttempt.Kind.AUTO,
            null,
            startedAt,
            finishedAt,
            "DMQ",
            outcome.httpStatus(),
            error,
            excerpt
          );
          log.error(
            "Outbox entry {} (accession {}) exhausted {} attempts and moved to the dead-message queue with its " +
            "complete payload; retry it once OpenELIS is reachable",
            entry.id(),
            entry.accession(),
            attemptsAfter
          );
        } else {
          Duration delay = backoff.delayAfter(attemptsAfter);
          store.markRetrying(entry.id(), finishedAt.plus(delay), outcome.httpStatus(), error, excerpt);
          store.recordAttempt(
            entry.id(),
            OutboxAttempt.Kind.AUTO,
            null,
            startedAt,
            finishedAt,
            "RETRY",
            outcome.httpStatus(),
            error,
            excerpt
          );
          log.warn(
            "Outbox entry {} not delivered (attempt {}/{}, status={}, error={}); retrying in {}s",
            entry.id(),
            attemptsAfter,
            properties.getRetry().getMaxAttempts(),
            outcome.httpStatus(),
            error,
            delay.toSeconds()
          );
        }
      }
      case DEAD_LETTER -> {
        store.markRejected(entry.id(), decision.reason(), outcome.httpStatus(), error, excerpt);
        store.recordAttempt(
          entry.id(),
          OutboxAttempt.Kind.AUTO,
          null,
          startedAt,
          finishedAt,
          "DMQ",
          outcome.httpStatus(),
          error,
          excerpt
        );
        log.error(
          "OpenELIS refused outbox entry {} (accession {}) with status {} ({}); held in the dead-message queue " +
          "with its complete payload",
          entry.id(),
          entry.accession(),
          outcome.httpStatus(),
          decision.reason()
        );
      }
    }
  }

  /**
   * Render an entry that was persisted on receipt but never rendered, because the bridge stopped in
   * between. The message is intact, so it is rendered now against current configuration rather than
   * asking the analyzer to send it again.
   */
  private void renderRecovered(OutboxEntry entry) {
    Optional<String> raw = store.rawPayload(entry.id());
    if (raw.isEmpty()) {
      store.markDeadLettered(entry.id(), FailureReason.RENDER_ERROR, "Received entry has no stored payload to render");
      return;
    }
    MessageEnvelope envelope = MessageEnvelope.builder()
      .protocol(entry.protocol())
      .transport(entry.transport())
      .sourceId(entry.sourceId())
      .sourcePort(entry.sourcePort() == null ? 0 : entry.sourcePort())
      .rawMessage(raw.get())
      .receivedAt(entry.receivedAt())
      .protocolAnalyzerHint(entry.protocolHint())
      .listenerPort(entry.listenerPort())
      .build();
    NormalizedBundleRenderer.Outcome outcome = renderer.render(envelope, client.targetUri().toString());
    if (outcome instanceof NormalizedBundleRenderer.Outcome.Failed failed) {
      store.markDeadLettered(entry.id(), failed.reason(), failed.message());
      log.error(
        "Could not render recovered outbox entry {} from {}: {}. Held in the dead-message queue with its " +
        "complete payload; fix the analyzer configuration and retry it",
        entry.id(),
        entry.sourceId(),
        failed.message()
      );
      return;
    }
    store.markRendered(entry.id(), ((NormalizedBundleRenderer.Outcome.Rendered) outcome).deliveries());
    log.info("Rendered outbox entry {} recovered from a previous run; it is queued for delivery", entry.id());
  }

  /**
   * OpenELIS's receipt reference, kept so an operator can confirm a delivery against OpenELIS rather
   * than taking the bridge's word for it. Older OpenELIS builds answer without one, in which case
   * the response summary itself is the reference.
   */
  private static String oeReceiptOf(String body) {
    if (body == null || body.isBlank()) {
      return null;
    }
    int key = body.indexOf("\"receiptId\"");
    if (key < 0) {
      return body;
    }
    int start = body.indexOf('"', body.indexOf(':', key) + 1);
    int end = start < 0 ? -1 : body.indexOf('"', start + 1);
    return start < 0 || end < 0 ? body : body.substring(start + 1, end);
  }

  public boolean isRunning() {
    return running;
  }

  public String inFlightId() {
    return inFlightId;
  }

  public Instant lastPollAt() {
    return lastPollAt;
  }
}
