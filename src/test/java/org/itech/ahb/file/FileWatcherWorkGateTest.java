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
  void claimsFollowTheActiveRegistrationAndItsDiscoveryPattern() throws Exception {
    Path selected = directory.resolve("export.csv");
    assertNull(watcher.tryClaimFile(directory.resolve("export.xlsx")), "discovery stays restricted to its glob");
    try (var receipt = watcher.tryClaimFile(selected)) {
      assertNotNull(receipt);
      assertNull(watcher.tryClaimFile(selected), "physical file exclusion still applies");
    }
    watcher.removeWatchRegistration(directory, "owner");
    assertNull(watcher.tryClaimFile(selected), "inactive connections admit no work");
  }

  @Test
  void stoppedWatcherNeverAdmitsNewWorkEvenAfterADirectoryPauseCloses() throws Exception {
    try (var pause = watcher.pauseDirectory(directory)) {
      watcher.stop();
    }
    try (var claim = watcher.tryClaimFile(directory.resolve("result.csv"))) {
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
    try (var worker = watcher.tryClaimFile(directory.resolve("busy.csv"))) {
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
      assertNull(watcher.tryClaimFile(directory.resolve("new.csv")));
    }
    watcher.stop();
  }

  @Test
  void nestedPausesBlockNewFilesUntilEveryCleanupScopeCloses() throws Exception {
    Path file = directory.resolve("result.csv");
    try (var outer = watcher.pauseDirectory(directory)) {
      try (var inner = watcher.pauseDirectory(directory)) {
        assertNull(watcher.tryClaimFile(file));
      }
      assertNull(watcher.tryClaimFile(file));
    }
    try (var claim = watcher.tryClaimFile(file)) {
      assertNotNull(claim);
    }
  }

  @Test
  void interruptedDrainReleasesItsPauseWithoutReleasingTheActiveWorker() throws Exception {
    Path busy = directory.resolve("busy.csv");
    try (var worker = watcher.tryClaimFile(busy)) {
      assertNotNull(worker);
      try {
        Thread.currentThread().interrupt();
        assertThrows(IOException.class, () -> watcher.pauseDirectory(directory));
        assertTrue(Thread.currentThread().isInterrupted());
      } finally {
        Thread.interrupted();
      }
      assertNull(watcher.tryClaimFile(busy));
      try (var other = watcher.tryClaimFile(directory.resolve("other.csv"))) {
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
    try (var worker = watcher.tryClaimFile(physical.resolve("result.csv"))) {
      assertNotNull(worker);
      assertNull(watcher.tryClaimFile(alias.resolve("result.csv")));
    }
    try (var pause = watcher.pauseDirectory(alias)) {
      assertNull(watcher.tryClaimFile(physical.resolve("new.csv")));
    }
    try (var worker = watcher.tryClaimFile(physical.resolve("new.csv"))) {
      assertNotNull(worker);
    }
  }
}
