package org.itech.ahb.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.commons.io.monitor.FileAlterationMonitor;
import org.itech.ahb.connection.AnalyzerRuntimeRegistry;
import org.itech.ahb.connection.AnalyzerRuntimeRegistry.AnalyzerEntry;
import org.itech.ahb.fhir.FileNameSelfDeclarationScanner;
import org.itech.ahb.file.FileConfig;
import org.itech.ahb.file.FileMessageHandler;
import org.itech.ahb.file.FileWatcher;
import org.itech.ahb.file.SqliteFileStateStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

class FileUploadConcurrencyTest {

  @TempDir
  Path directory;

  private static final String ANALYZER = "connection-owner";

  @Test
  void shutdownDrainsUploadsAndRejectsNewFilesBeforeReturning() throws Exception {
    SqliteFileStateStore store = new SqliteFileStateStore(directory.resolve("state.db"));
    FileMessageHandler handler = mock(FileMessageHandler.class);
    FileWatcher watcher = watcher(handler, store);
    FileUploadController controller = controller(watcher, handler);
    CountDownLatch processing = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch stopping = new CountDownLatch(1);
    FileAlterationMonitor monitor = mock(FileAlterationMonitor.class);
    doAnswer(invocation -> {
      stopping.countDown();
      return null;
    })
      .when(monitor)
      .stop();
    ReflectionTestUtils.setField(watcher, "monitor", monitor);
    var executor = Executors.newFixedThreadPool(2);
    try {
      doAnswer(invocation -> {
        processing.countDown();
        assertTrue(release.await(5, TimeUnit.SECONDS), "upload was not released");
        return org.itech.ahb.normalizer.MessageEnvelope.builder().outboxReceiptId("receipt").build();
      })
        .when(handler)
        .receiveBytes(any(), eq(ANALYZER), eq("RESULT"), any());
      var upload = executor.submit(
        () -> controller.uploadFile(ANALYZER, "RESULT", multipart("original"), new MockHttpServletResponse())
      );
      assertTrue(processing.await(5, TimeUnit.SECONDS));
      var shutdown = executor.submit(watcher::stop);
      assertTrue(stopping.await(5, TimeUnit.SECONDS));
      assertThrows(
        java.util.concurrent.TimeoutException.class,
        () -> shutdown.get(200, TimeUnit.MILLISECONDS),
        "shutdown must wait for caller-thread uploads, not only its own executors"
      );
      MockHttpServletResponse rejected = new MockHttpServletResponse();
      controller.uploadFile(
        ANALYZER,
        "RESULT",
        new MockMultipartFile("file", "new.csv", "text/csv", new byte[] { 1 }),
        rejected
      );
      assertEquals(409, rejected.getStatus());
      assertFalse(Files.exists(directory.resolve("new.csv")));
      release.countDown();
      upload.get(5, TimeUnit.SECONDS);
      shutdown.get(5, TimeUnit.SECONDS);
      String hash = ReflectionTestUtils.invokeMethod(watcher, "calculateFileHash", directory.resolve("result.csv"));
      assertEquals("PROCESSED", store.get(ANALYZER, hash).orElseThrow().status().name());
      assertNull(watcher.tryClaimFile(directory.resolve("after.csv"), ANALYZER));
    } finally {
      release.countDown();
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
      watcher.stop();
      store.close();
    }
  }

  @Test
  void shutdownCancelsDelayedTasksWithoutLosingDurableRetryState() throws Exception {
    Path database = directory.resolve("state.db");
    Path file = directory.resolve("result.csv");
    Files.writeString(file, "original");
    SqliteFileStateStore store = new SqliteFileStateStore(database);
    FileMessageHandler handler = mock(FileMessageHandler.class);
    FileConfig config = new FileConfig();
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

  @Test
  void watcherCannotTakeOverAnUploadEvenAfterItsRetryDelayExpires() throws Exception {
    SqliteFileStateStore store = new SqliteFileStateStore(directory.resolve("state.db"));
    try {
      FileMessageHandler handler = mock(FileMessageHandler.class);
      FileWatcher watcher = watcher(handler, store);
      FileUploadController controller = controller(watcher, handler);
      Path file = directory.resolve("result.csv");
      CountDownLatch processing = new CountDownLatch(1);
      CountDownLatch release = new CountDownLatch(1);
      var executor = Executors.newSingleThreadExecutor();
      try {
        doAnswer(invocation -> {
          processing.countDown();
          assertTrue(release.await(5, TimeUnit.SECONDS), "upload processing was not released");
          return org.itech.ahb.normalizer.MessageEnvelope.builder().outboxReceiptId("receipt").build();
        })
          .when(handler)
          .receiveBytes(eq(file), eq(ANALYZER), eq("RESULT"), any());
        MockHttpServletResponse response = new MockHttpServletResponse();
        var upload = executor.submit(() -> controller.uploadFile(ANALYZER, "RESULT", multipart("original"), response));
        assertTrue(processing.await(5, TimeUnit.SECONDS), "upload never reached processing");
        String hash = org.itech.ahb.file.FileDeliveryIdentity.contentHash(
          "original".getBytes(java.nio.charset.StandardCharsets.UTF_8)
        );
        assertFalse(Files.exists(file));
        store.setNextAttemptAt(ANALYZER, hash, Instant.EPOCH);

        ReflectionTestUtils.invokeMethod(watcher, "processFileWithRetry", file);

        verify(handler, never()).receiveBytes(eq(file), eq(ANALYZER), isNull(), any(byte[].class));
        assertEquals("RETRYING", store.get(ANALYZER, hash).orElseThrow().status().name());
        release.countDown();
        upload.get(5, TimeUnit.SECONDS);
        assertEquals("PROCESSED", store.get(ANALYZER, hash).orElseThrow().status().name());
        assertTrue(response.getContentAsString().contains("banner success"));
      } finally {
        release.countDown();
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        watcher.stop();
      }
    } finally {
      store.close();
    }
  }

  @Test
  void uploadCannotOverwriteAFileWhileTheWatcherProcessesIt() throws Exception {
    SqliteFileStateStore store = new SqliteFileStateStore(directory.resolve("state.db"));
    try {
      FileMessageHandler handler = mock(FileMessageHandler.class);
      FileWatcher watcher = watcher(handler, store);
      FileUploadController controller = controller(watcher, handler);
      Path file = directory.resolve("result.csv");
      Files.writeString(file, "original");
      CountDownLatch processing = new CountDownLatch(1);
      CountDownLatch release = new CountDownLatch(1);
      var executor = Executors.newSingleThreadExecutor();
      try {
        doAnswer(invocation -> {
          processing.countDown();
          assertTrue(release.await(5, TimeUnit.SECONDS), "watcher processing was not released");
          return org.itech.ahb.normalizer.MessageEnvelope.builder().outboxReceiptId("receipt").build();
        })
          .when(handler)
          .receiveBytes(eq(file), eq(ANALYZER), isNull(), any(byte[].class));
        var work = executor.submit(() -> ReflectionTestUtils.invokeMethod(watcher, "processFileWithRetry", file));
        assertTrue(processing.await(5, TimeUnit.SECONDS), "watcher never reached processing");
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.uploadFile(ANALYZER, "RESULT", multipart("replacement"), response);

        assertEquals(409, response.getStatus());
        assertEquals("original", Files.readString(file));
        verify(handler, never()).receiveBytes(any(), anyString(), anyString(), any());
        release.countDown();
        work.get(5, TimeUnit.SECONDS);
      } finally {
        release.countDown();
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        watcher.stop();
      }
    } finally {
      store.close();
    }
  }

  private FileWatcher watcher(FileMessageHandler handler, SqliteFileStateStore store) throws Exception {
    FileWatcher watcher = new FileWatcher(new FileConfig(), handler, store);
    // Invoke real worker code deterministically; polling timing is not the subject of these tests.
    watcher.addWatchDirectory(directory, "*.csv", ANALYZER);
    return watcher;
  }

  @Test
  void rejectedReceiptNeverLeavesAFileForTheWatcherToReinterpret() throws Exception {
    Path database = directory.resolve("state.db");
    Path file = directory.resolve("result.csv");
    SqliteFileStateStore store = new SqliteFileStateStore(database);
    try {
      FileMessageHandler handler = mock(FileMessageHandler.class);
      FileWatcher watcher = watcher(handler, store);
      try {
        doThrow(new FileMessageHandler.FileProcessingException("outbox storage unavailable"))
          .when(handler)
          .receiveBytes(eq(file), eq(ANALYZER), eq("RESULT"), any());
        MockHttpServletResponse response = new MockHttpServletResponse();
        controller(watcher, handler).uploadFile(ANALYZER, "RESULT", multipart("original"), response);
        assertEquals(500, response.getStatus());
        assertFalse(Files.exists(file));
      } finally {
        watcher.stop();
      }
    } finally {
      store.close();
    }
    SqliteFileStateStore reopened = new SqliteFileStateStore(database);
    try {
      assertFalse(Files.exists(file), "restart must not expose an unacknowledged upload to profile-default parsing");
    } finally {
      reopened.close();
    }
  }

  @Test
  void deactivationDrainsUploadsAndRejectsAStaleControllerRegistration() throws Exception {
    SqliteFileStateStore store = new SqliteFileStateStore(directory.resolve("state.db"));
    FileMessageHandler handler = mock(FileMessageHandler.class);
    FileWatcher watcher = watcher(handler, store);
    FileUploadController controller = controller(watcher, handler);
    CountDownLatch processing = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch removing = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(2);
    try {
      doAnswer(invocation -> {
        processing.countDown();
        assertTrue(release.await(5, TimeUnit.SECONDS), "upload was not released");
        return org.itech.ahb.normalizer.MessageEnvelope.builder().outboxReceiptId("receipt").build();
      })
        .when(handler)
        .receiveBytes(any(), eq(ANALYZER), eq("RESULT"), any());
      var upload = executor.submit(
        () -> controller.uploadFile(ANALYZER, "RESULT", multipart("original"), new MockHttpServletResponse())
      );
      assertTrue(processing.await(5, TimeUnit.SECONDS));
      var removal = executor.submit(() -> {
        removing.countDown();
        return watcher.removeWatchRegistration(directory, ANALYZER);
      });
      assertTrue(removing.await(5, TimeUnit.SECONDS));
      assertThrows(
        java.util.concurrent.TimeoutException.class,
        () -> removal.get(200, TimeUnit.MILLISECONDS),
        "deactivation must not return while upload processing is still active"
      );
      release.countDown();
      upload.get(5, TimeUnit.SECONDS);
      assertTrue(removal.get(5, TimeUnit.SECONDS));

      MockHttpServletResponse response = new MockHttpServletResponse();
      controller.uploadFile(ANALYZER, "RESULT", multipart("replacement"), response);

      assertEquals(409, response.getStatus(), "a previously resolved registry entry must not authorize new work");
      assertEquals("original", Files.readString(directory.resolve("result.csv")));
    } finally {
      release.countDown();
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
      watcher.stop();
      store.close();
    }
  }

  @Test
  void failedWorkerKeepsOwnershipUntilItsRetryStateIsWritten() throws Exception {
    SqliteFileStateStore store = spy(new SqliteFileStateStore(directory.resolve("state.db")));
    FileMessageHandler handler = mock(FileMessageHandler.class);
    FileWatcher watcher = watcher(handler, store);
    CountDownLatch updatingState = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    var executor = Executors.newSingleThreadExecutor();
    Path file = directory.resolve("result.csv");
    Files.writeString(file, "original");
    try {
      doThrow(new FileMessageHandler.FileProcessingException("receiver unavailable"))
        .when(handler)
        .receiveBytes(eq(file), eq(ANALYZER), isNull(), any(byte[].class));
      doAnswer(invocation -> {
        updatingState.countDown();
        assertTrue(release.await(5, TimeUnit.SECONDS), "retry-state write was not released");
        return invocation.callRealMethod();
      })
        .when(store)
        .incrementAttempts(eq(ANALYZER), anyString(), anyString());
      var work = executor.submit(() -> ReflectionTestUtils.invokeMethod(watcher, "processFileWithRetry", file));
      assertTrue(updatingState.await(5, TimeUnit.SECONDS), "worker never recorded its failure");
      MockHttpServletResponse response = new MockHttpServletResponse();

      controller(watcher, handler).uploadFile(ANALYZER, "RESULT", multipart("replacement"), response);

      assertEquals(409, response.getStatus());
      assertEquals("original", Files.readString(file));
      release.countDown();
      work.get(5, TimeUnit.SECONDS);
    } finally {
      release.countDown();
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
      watcher.stop();
      store.close();
    }
  }

  private FileUploadController controller(FileWatcher watcher, FileMessageHandler handler) {
    AnalyzerRuntimeRegistry registry = new AnalyzerRuntimeRegistry();
    AnalyzerEntry entry = new AnalyzerEntry();
    entry.setId(ANALYZER);
    entry.setExpectedProtocol("FILE");
    entry.setFileDirectory(directory.toString());
    entry.setMappedTestCodes(Set.of("RESULT"));
    registry.register("opaque-connection-key", entry);
    return new FileUploadController(registry, handler, mock(FileNameSelfDeclarationScanner.class), watcher);
  }

  private MockMultipartFile multipart(String content) {
    return new MockMultipartFile(
      "file",
      "result.csv",
      "text/csv",
      content.getBytes(java.nio.charset.StandardCharsets.UTF_8)
    );
  }
}
