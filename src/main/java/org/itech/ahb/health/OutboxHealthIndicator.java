package org.itech.ahb.health;

import java.time.Instant;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.itech.ahb.outbox.OutboxDispatcher;
import org.itech.ahb.outbox.OutboxState;
import org.itech.ahb.outbox.OutboxStore;
import org.springframework.boot.actuate.autoconfigure.health.ConditionalOnEnabledHealthIndicator;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Reports whether the bridge can still keep its delivery promise.
 *
 * <p>Deliberately does not go DOWN because results are queued: an OpenELIS outage is exactly the
 * situation the outbox exists to ride out, and a bridge that reported itself unhealthy for it would
 * invite an operator to restart or redeploy the one process holding those results.
 *
 * <p>It goes DOWN for the two conditions that mean the promise is broken: the store could not be
 * read, or it had to be replaced after corruption, which means undelivered results were lost.
 */
@Component("outbox")
@ConditionalOnEnabledHealthIndicator("outbox")
@Slf4j
public class OutboxHealthIndicator implements HealthIndicator {

  private final OutboxStore store;
  private final OutboxDispatcher dispatcher;

  public OutboxHealthIndicator(OutboxStore store, OutboxDispatcher dispatcher) {
    this.store = store;
    this.dispatcher = dispatcher;
  }

  @Override
  public Health health() {
    if (store.recoveredFromCorruption()) {
      return Health.down()
        .withDetail("reason", "outbox_replaced_after_corruption")
        .withDetail(
          "message",
          "The delivery outbox was unreadable and has been replaced. Undelivered results it held are " +
          "not recoverable from the bridge: preserve the renamed file and reconcile against OpenELIS."
        )
        .build();
    }
    try {
      Map<OutboxState, Integer> counts = store.countsByState();
      int undelivered =
        counts.get(OutboxState.RECEIVED) + counts.get(OutboxState.PENDING) + counts.get(OutboxState.RETRYING);
      Health.Builder health = Health.up()
        .withDetail("undelivered", undelivered)
        .withDetail("deadLettered", counts.get(OutboxState.DMQ))
        .withDetail("delivered", counts.get(OutboxState.DELIVERED))
        .withDetail("dispatcherRunning", dispatcher.isRunning());
      store
        .oldestUndeliveredReceivedAt()
        .ifPresent(
          received ->
            health.withDetail(
              "oldestUndeliveredAgeSeconds",
              Math.max(0, Instant.now().getEpochSecond() - received.getEpochSecond())
            )
        );
      return health.build();
    } catch (RuntimeException e) {
      log.error("Delivery outbox is not readable", e);
      return Health.down().withDetail("reason", "outbox_unreadable").withException(e).build();
    }
  }
}
