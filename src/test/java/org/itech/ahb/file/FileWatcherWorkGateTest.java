package org.itech.ahb.file;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

class FileWatcherWorkGateTest {

  @TempDir
  Path directory;

  private FileWatcher watcher;

  @BeforeEach
  void setUp() throws IOException {
    watcher = new FileWatcher(new FileConfig(), null, null);
    watcher.addWatchDirectory(directory, "*.csv", "owner");
  }

  @AfterEach
  void tearDown() {
    watcher.stop();
  }

  @Test
  void manualUploadSelectsAnActiveConnectionIndependentlyOfTheDiscoveryPattern() throws Exception {
    Path selected = directory.resolve("manual-export.xlsx");
    assertNull(watcher.tryClaimFile(selected, null), "watcher discovery remains restricted to its configured glob");
    try (var receipt = watcher.tryClaimFile(selected, "owner")) {
      assertNotNull(receipt);
      assertNull(watcher.tryClaimFile(selected, "owner"), "physical file exclusion still applies");
    }
    assertNull(watcher.tryClaimFile(selected, "different-analyzer"));
    watcher.removeWatchRegistration(directory, "owner");
    assertNull(watcher.tryClaimFile(selected, "owner"), "inactive connections cannot admit uploads");
  }

  @Test
  void stoppedWatcherNeverAdmitsNewWorkEvenAfterADirectoryPauseCloses() throws Exception {
    try (var pause = watcher.pauseDirectory(directory)) {
      watcher.stop();
    }
    try (var claim = watcher.tryClaimFile(directory.resolve("result.csv"), "owner")) {
      assertNull(claim);
    }
  }

  @Test
  void stoppedWatcherCannotBeReactivatedWithClosedExecutors() {
    watcher.stop();
    assertThrows(IOException.class, () -> watcher.start());
    assertThrows(IOException.class, () -> watcher.addWatchDirectory(directory, "*.csv", "new-owner"));
  }

  @Test
  void interruptedShutdownDoesNotPretendTheActiveClaimWasCancelled() throws Exception {
    try (var worker = watcher.tryClaimFile(directory.resolve("busy.csv"), "owner")) {
      assertNotNull(worker);
      try {
        Thread.currentThread().interrupt();
        assertThrows(IllegalStateException.class, watcher::stop);
        assertTrue(Thread.currentThread().isInterrupted());
      } finally {
        Thread.interrupted();
      }
      var claims = (java.util.Set<?>) ReflectionTestUtils.getField(watcher, "processingFiles");
      assertNotNull(claims);
      assertEquals(1, claims.size(), "an interrupted wait must not release somebody else's work");
      assertNull(watcher.tryClaimFile(directory.resolve("new.csv"), "owner"));
    }
    watcher.stop();
  }

  @Test
  void nestedPausesBlockNewFilesUntilEveryCleanupScopeCloses() throws Exception {
    Path file = directory.resolve("result.csv");
    try (var outer = watcher.pauseDirectory(directory)) {
      try (var inner = watcher.pauseDirectory(directory)) {
        assertNull(watcher.tryClaimFile(file, "owner"));
      }
      assertNull(watcher.tryClaimFile(file, "owner"));
    }
    try (var claim = watcher.tryClaimFile(file, "owner")) {
      assertNotNull(claim);
    }
  }

  @Test
  void interruptedDrainReleasesItsPauseWithoutReleasingTheActiveWorker() throws Exception {
    Path busy = directory.resolve("busy.csv");
    try (var worker = watcher.tryClaimFile(busy, "owner")) {
      assertNotNull(worker);
      try {
        Thread.currentThread().interrupt();
        assertThrows(IOException.class, () -> watcher.pauseDirectory(directory));
        assertTrue(Thread.currentThread().isInterrupted());
      } finally {
        Thread.interrupted();
      }
      assertNull(watcher.tryClaimFile(busy, "owner"));
      try (var other = watcher.tryClaimFile(directory.resolve("other.csv"), "owner")) {
        assertNotNull(other, "failed drain must not leave the directory permanently paused");
      }
    }
  }

  @Test
  void physicalDirectoryAliasesShareTheSameClaimAndPause() throws Exception {
    Path physical = Files.createDirectory(directory.resolve("physical"));
    Path alias = Files.createSymbolicLink(directory.resolve("alias"), physical);
    watcher.addWatchDirectory(physical, "*.csv", "owner");
    watcher.addWatchDirectory(alias, "*.csv", "owner");
    try (var worker = watcher.tryClaimFile(physical.resolve("result.csv"), "owner")) {
      assertNotNull(worker);
      assertNull(watcher.tryClaimFile(alias.resolve("result.csv"), "owner"));
    }
    try (var pause = watcher.pauseDirectory(alias)) {
      assertNull(watcher.tryClaimFile(physical.resolve("new.csv"), "owner"));
    }
    try (var worker = watcher.tryClaimFile(physical.resolve("new.csv"), "owner")) {
      assertNotNull(worker);
    }
  }
}
