package org.itech.ahb.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.itech.ahb.connection.AnalyzerConnectionCatalog;
import org.itech.ahb.connection.AnalyzerConnectionCatalog.FileDirectoryClaim;
import org.itech.ahb.connection.AnalyzerRuntimeRegistry;
import org.itech.ahb.connection.AnalyzerRuntimeRegistry.AnalyzerEntry;
import org.itech.ahb.file.FileConfig;
import org.itech.ahb.file.FileStateStore;
import org.itech.ahb.file.FileWatcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;

class BridgeAdminControllerTest {

  @TempDir
  Path watchDirectory;

  private final AnalyzerConnectionCatalog connections = mock(AnalyzerConnectionCatalog.class);
  private final FileWatcher watcher = new FileWatcher(new FileConfig(), null, null);

  @AfterEach
  void stopWatcher() {
    watcher.stop();
  }

  @Test
  void inactiveSavedConnectionStillPreventsSharedDirectoryReset() throws Exception {
    Path pending = Files.writeString(watchDirectory.resolve("result.csv"), "data");
    AnalyzerRuntimeRegistry registry = new AnalyzerRuntimeRegistry();
    register(registry, "active", watchDirectory, "*.csv");
    when(connections.fileDirectoryClaims()).thenReturn(
      List.of(
        new FileDirectoryClaim("active", watchDirectory, "*.csv"),
        new FileDirectoryClaim("inactive", watchDirectory.resolve("."), "*.csv")
      )
    );
    FileStateStore store = mock(FileStateStore.class);
    var response = new BridgeAdminController(registry, store, connections, watcher).reset("active");
    assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
    assertEquals("data", Files.readString(pending));
    verifyNoInteractions(store);
  }

  @Test
  void httpFileConnectionHasNoWatchDirectoryToReset() {
    AnalyzerEntry entry = new AnalyzerEntry();
    entry.setId("http");
    entry.setExpectedProtocol("FILE");
    entry.setInboundTransport("HTTP");
    entry.setFilePattern("*.csv");
    AnalyzerRuntimeRegistry registry = new AnalyzerRuntimeRegistry();
    registry.register("connection:http", entry);
    var response = new BridgeAdminController(registry, null, connections, watcher).reset("http");
    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals(List.of(), response.getBody().get("watchDirectories"));
    assertEquals(0, response.getBody().get("filesRemoved"));
  }

  @Test
  void resetCleansTheActualDirectoryForAConnectionScopedFileRegistryKey() throws Exception {
    Path staleResult = Files.writeString(watchDirectory.resolve("stale-result.xlsx"), "fixture");
    AnalyzerEntry analyzer = new AnalyzerEntry();
    analyzer.setId("5");
    analyzer.setBridgeConnectionId("31a62ad1-2356-4b4d-be21-b479f57a3dbf");
    analyzer.setExpectedProtocol("FILE");
    analyzer.setFilePattern("*.xlsx");
    analyzer.setFileDirectory(watchDirectory.toString());

    AnalyzerRuntimeRegistry registry = mock(AnalyzerRuntimeRegistry.class);
    when(registry.getRegisteredAnalyzers()).thenReturn(
      Map.of(watchDirectory + "#31a62ad1-2356-4b4d-be21-b479f57a3dbf", analyzer)
    );
    FileStateStore stateStore = mock(FileStateStore.class);
    when(stateStore.deleteAllForAnalyzer("5")).thenReturn(1);

    var response = new BridgeAdminController(registry, stateStore, connections, watcher).reset("5");

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals(1, response.getBody().get("stateRowsRemoved"));
    assertEquals(1, response.getBody().get("filesRemoved"));
    assertEquals(List.of(watchDirectory.toRealPath().toString()), response.getBody().get("watchDirectories"));
    assertFalse(Files.exists(staleResult));
  }

  @Test
  void sharedDirectoryIsRejectedBeforeAnyFilesOrStateAreRemoved() throws Exception {
    Path first = Files.writeString(watchDirectory.resolve("first.csv"), "first data");
    Path second = Files.writeString(watchDirectory.resolve("second.csv"), "second data");
    AnalyzerRuntimeRegistry registry = new AnalyzerRuntimeRegistry();
    register(registry, "first", watchDirectory, "first*.csv");
    register(registry, "second", watchDirectory.resolve("."), "second*.csv");
    FileStateStore store = mock(FileStateStore.class);

    var response = new BridgeAdminController(registry, store, connections, watcher).reset("first");

    assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
    assertEquals(false, response.getBody().get("reset"));
    assertEquals("first data", Files.readString(first));
    assertEquals("second data", Files.readString(second));
    verifyNoInteractions(store);
  }

  @Test
  void checksAllDirectoriesBeforeDeletingFromAnExclusiveDirectory() throws Exception {
    Path exclusive = Files.createDirectory(watchDirectory.resolve("exclusive"));
    Path shared = Files.createDirectory(watchDirectory.resolve("shared"));
    Path pending = Files.writeString(exclusive.resolve("pending.csv"), "data");
    AnalyzerRuntimeRegistry registry = new AnalyzerRuntimeRegistry();
    register(registry, "first", exclusive, "*.csv");
    register(registry, "first", shared, "first*.csv");
    register(registry, "second", shared, "second*.csv");
    FileStateStore store = mock(FileStateStore.class);

    var response = new BridgeAdminController(registry, store, connections, watcher).reset("first");

    assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
    assertTrue(Files.exists(pending));
    verifyNoInteractions(store);
  }

  @Test
  void exclusiveResetPreservesUnclaimedFilesAndSymbolicLinks() throws Exception {
    Path owned = Files.writeString(watchDirectory.resolve("result.csv"), "data");
    Path unclaimed = Files.writeString(watchDirectory.resolve("notes.txt"), "notes");
    Path link = Files.createSymbolicLink(watchDirectory.resolve("link.csv"), unclaimed);
    AnalyzerRuntimeRegistry registry = new AnalyzerRuntimeRegistry();
    register(registry, "first", watchDirectory, "*.csv");

    var response = new BridgeAdminController(registry, null, connections, watcher).reset("first");

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertFalse(Files.exists(owned));
    assertEquals("notes", Files.readString(unclaimed));
    assertTrue(Files.isSymbolicLink(link));
    assertEquals(1, response.getBody().get("filesRemoved"));
  }

  @Test
  void directoryAliasesCannotHideSharedOwnership() throws Exception {
    Path physical = Files.createDirectory(watchDirectory.resolve("physical"));
    Path alias = Files.createSymbolicLink(watchDirectory.resolve("alias"), physical);
    Path pending = Files.writeString(physical.resolve("result.csv"), "data");
    AnalyzerRuntimeRegistry registry = new AnalyzerRuntimeRegistry();
    register(registry, "first", physical, "*.csv");
    register(registry, "second", alias, "*.csv");
    FileStateStore store = mock(FileStateStore.class);

    var response = new BridgeAdminController(registry, store, connections, watcher).reset("first");

    assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
    assertTrue(Files.exists(pending));
    verifyNoInteractions(store);
  }

  private void register(AnalyzerRuntimeRegistry registry, String id, Path directory, String pattern) {
    AnalyzerEntry entry = new AnalyzerEntry();
    entry.setId(id);
    entry.setBridgeConnectionId("connection-" + id);
    entry.setExpectedProtocol("FILE");
    entry.setFilePattern(pattern);
    entry.setFileDirectory(directory.toString());
    registry.register(directory + "#connection-" + id, entry);
  }

  @Test
  void interruptedDrainDoesNotDeleteFilesOrClearTracking() throws Exception {
    Path pending = Files.writeString(watchDirectory.resolve("pending.csv"), "data");
    AnalyzerRuntimeRegistry registry = new AnalyzerRuntimeRegistry();
    register(registry, "first", watchDirectory, "*.csv");
    watcher.addWatchDirectory(watchDirectory, "*.csv", "first");
    FileStateStore store = mock(FileStateStore.class);
    try (var activeWork = watcher.tryClaimFile(pending)) {
      org.junit.jupiter.api.Assertions.assertNotNull(activeWork);
      try {
        Thread.currentThread().interrupt();
        var response = new BridgeAdminController(registry, store, connections, watcher).reset("first");
        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertTrue(Thread.currentThread().isInterrupted());
      } finally {
        Thread.interrupted();
      }
      assertEquals("data", Files.readString(pending));
      verifyNoInteractions(store);
    }
    try (var resumed = watcher.tryClaimFile(pending)) {
      org.junit.jupiter.api.Assertions.assertNotNull(resumed, "failed reset must release its admission pause");
    }
  }
}
