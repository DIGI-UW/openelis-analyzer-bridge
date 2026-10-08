package org.itech.ahb.file;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

class FileWatcherShutdownTest {

  @TempDir
  Path directory;

  private static final String ANALYZER = "connection-owner";

  @Test
  void shutdownCancelsDelayedTasksWithoutLosingDurableRetryState() throws Exception {
    Path database = directory.resolve("state.db");
    Path file = directory.resolve("result.csv");
    Files.writeString(file, "original");
    SqliteFileStateStore store = new SqliteFileStateStore(database);
    FileMessageHandler handler = mock(FileMessageHandler.class);
    FileConfig config = new FileConfig();
    config.setImportRoots(java.util.List.of(directory.toString()));
    config.setRetryDelayMs(TimeUnit.MINUTES.toMillis(5));
    FileWatcher watcher = new FileWatcher(config, handler, store);
    watcher.addWatchDirectory(directory, "*.csv", ANALYZER);
    var executor = Executors.newSingleThreadExecutor();
    String hash;
    Instant deadline;
    try {
      doThrow(new FileMessageHandler.FileProcessingException("receiver unavailable"))
        .when(handler)
        .receiveBytes(eq(file), eq(ANALYZER), isNull(), any(byte[].class));
      ReflectionTestUtils.invokeMethod(watcher, "processFileWithRetry", file);
      hash = ReflectionTestUtils.invokeMethod(watcher, "calculateFileHash", file);
      var before = store.get(ANALYZER, hash).orElseThrow();
      assertEquals("RETRYING", before.status().name());
      deadline = before.nextAttemptAt();
      assertNotNull(deadline);
      assertTrue(deadline.isAfter(Instant.now()));
      var scheduler = (java.util.concurrent.ScheduledThreadPoolExecutor) ReflectionTestUtils.getField(
        watcher,
        "stabilityChecker"
      );
      assertNotNull(scheduler);
      assertEquals(1, scheduler.getQueue().size(), "there must be a real pending retry to cancel");

      executor.submit(watcher::stop).get(5, TimeUnit.SECONDS);

      assertTrue(scheduler.isTerminated());
      assertEquals(deadline, store.get(ANALYZER, hash).orElseThrow().nextAttemptAt());
      verify(handler, times(1)).receiveBytes(eq(file), eq(ANALYZER), isNull(), any(byte[].class));
    } finally {
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
      watcher.stop();
      store.close();
    }
    SqliteFileStateStore reopened = new SqliteFileStateStore(database);
    try {
      var retry = reopened.get(ANALYZER, hash).orElseThrow();
      assertEquals("RETRYING", retry.status().name());
      assertEquals(deadline, retry.nextAttemptAt());
      assertEquals("original", Files.readString(file));
    } finally {
      reopened.close();
    }
  }
}
