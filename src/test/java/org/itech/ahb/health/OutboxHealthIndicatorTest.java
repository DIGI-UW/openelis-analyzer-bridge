package org.itech.ahb.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.itech.ahb.model.Protocol;
import org.itech.ahb.model.Transport;
import org.itech.ahb.outbox.OutboxDispatcher;
import org.itech.ahb.outbox.OutboxStore;
import org.itech.ahb.outbox.Receipt;
import org.itech.ahb.outbox.ReceivedMessage;
import org.itech.ahb.outbox.RenderedDelivery;
import org.itech.ahb.outbox.SqliteOutboxStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

class OutboxHealthIndicatorTest {

  private static OutboxDispatcher runningDispatcher() {
    OutboxDispatcher dispatcher = mock(OutboxDispatcher.class);
    when(dispatcher.isRunning()).thenReturn(true);
    return dispatcher;
  }

  @Test
  @DisplayName("stays UP while results are queued, because riding out an outage is the job")
  void queuedResultsDoNotMakeTheBridgeUnhealthy(@TempDir Path dir) {
    try (SqliteOutboxStore store = new SqliteOutboxStore(dir.resolve("outbox.db"))) {
      Receipt receipt = store.receive(
        new ReceivedMessage("10.0.0.1", 12001, Protocol.ASTM, Transport.TCP, null, "H|raw\rR|1\rL|1", null, Instant.now())
      );
      store.markRendered(
        receipt.id(),
        List.of(new RenderedDelivery("astm-v1:a", "ACC-1", "{}", "conn", "analyzer", "profile", 1, "http://oe"))
      );

      Health health = new OutboxHealthIndicator(store, runningDispatcher()).health();

      assertEquals(
        Status.UP,
        health.getStatus(),
        "reporting unhealthy for a queue invites a restart of the one process holding those results"
      );
      assertEquals(1, health.getDetails().get("undelivered"));
      assertTrue(health.getDetails().containsKey("oldestUndeliveredAgeSeconds"));
    }
  }

  @Test
  @DisplayName("goes DOWN when the store had to be replaced, because results were lost")
  void corruptionRecoveryIsReported() {
    OutboxStore store = mock(OutboxStore.class);
    when(store.recoveredFromCorruption()).thenReturn(true);

    Health health = new OutboxHealthIndicator(store, runningDispatcher()).health();

    assertEquals(Status.DOWN, health.getStatus());
    assertEquals("outbox_replaced_after_corruption", health.getDetails().get("reason"));
  }

  @Test
  @DisplayName("goes DOWN when the store cannot be read at all")
  void unreadableStoreIsReported() {
    OutboxStore store = mock(OutboxStore.class);
    when(store.recoveredFromCorruption()).thenReturn(false);
    when(store.countsByState()).thenThrow(new IllegalStateException("database is locked"));

    Health health = new OutboxHealthIndicator(store, runningDispatcher()).health();

    assertEquals(Status.DOWN, health.getStatus());
    assertEquals("outbox_unreadable", health.getDetails().get("reason"));
  }
}
