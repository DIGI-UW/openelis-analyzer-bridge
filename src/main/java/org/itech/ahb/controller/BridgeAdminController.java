package org.itech.ahb.controller;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.itech.ahb.connection.AnalyzerConnectionCatalog;
import org.itech.ahb.connection.AnalyzerRuntimeRegistry;
import org.itech.ahb.connection.AnalyzerRuntimeRegistry.AnalyzerEntry;
import org.itech.ahb.file.FileStateStore;
import org.itech.ahb.file.FileWatcher;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin operations for development / iteration.
 *
 * <p>Sits behind the existing {@code /admin/**} security rule (HTTP Basic
 * with bridge admin credentials) and exposes per-analyzer state-reset so
 * demo / harness suites can re-run a QC scenario without stale CSV files
 * accumulating in the watch directory and racing with new files on the
 * SQLite {@code (analyzer_id, content_hash)} row.
 */
@RestController
@RequestMapping("/admin")
@Slf4j
public class BridgeAdminController {

  private final AnalyzerRuntimeRegistry registry;
  private final FileStateStore fileStateStore;
  private final AnalyzerConnectionCatalog connections;
  private final FileWatcher fileWatcher;

  // FileStateStore only exists when file handling is enabled
  // (StateStoreConfig is @ConditionalOnProperty bridge.file.enabled). The
  // admin controller must still load (and serve watch-dir cleanup) when
  // file mode is off, so the store is an optional dependency — null then,
  // and the SQLite-row reset below is simply skipped.
  public BridgeAdminController(
    AnalyzerRuntimeRegistry registry,
    @Nullable FileStateStore fileStateStore,
    AnalyzerConnectionCatalog connections,
    FileWatcher fileWatcher
  ) {
    this.registry = registry;
    this.fileStateStore = fileStateStore;
    this.connections = connections;
    this.fileWatcher = fileWatcher;
  }

  /**
   * Reset bridge-side state for a single analyzer:
   * <ol>
   *   <li>Verify that every target directory is exclusive to this analyzer</li>
   *   <li>Pause new FILE work and drain existing watcher processing</li>
   *   <li>Delete regular files matching its saved file patterns, without following links</li>
   *   <li>Clear file tracking only after the file cleanup succeeds</li>
   * </ol>
   *
   * <p>Idempotent. Safe to call when the analyzer has nothing to clean.
   * Returns counts so callers can verify (and log) the reset took effect.
   *
   * @param analyzerId OE analyzer id (numeric string), required
   * @return JSON with {@code stateRowsRemoved}, {@code filesRemoved},
   *         {@code watchDirectories}
   */
  @PostMapping("/reset")
  public ResponseEntity<Map<String, Object>> reset(@RequestParam String analyzerId) {
    if (analyzerId == null || analyzerId.isBlank()) {
      return ResponseEntity.badRequest().body(Map.of("reset", false, "error", "analyzerId is required"));
    }

    // Keep saved ownership stable, but release the registry while draining:
    // in-flight workers need registry access to finish delivery.
    synchronized (connections) {
      List<FileWatcher.DirectoryPause> pauses = new ArrayList<>();
      try {
        Map<Path, Set<String>> plan;
        synchronized (registry) {
          plan = exclusiveDirectoryPlan(analyzerId);
        }
        for (Path directory : plan.keySet()) {
          pauses.add(fileWatcher.pauseDirectory(directory));
        }
        synchronized (registry) {
          if (!plan.equals(exclusiveDirectoryPlan(analyzerId))) {
            throw new IllegalArgumentException("FILE ownership changed while preparing reset; retry reset");
          }
          return resetExclusive(analyzerId, plan);
        }
      } catch (IOException | IllegalArgumentException exception) {
        return ResponseEntity.status(409).body(Map.of("reset", false, "error", exception.getMessage()));
      } finally {
        for (int index = pauses.size() - 1; index >= 0; index--) {
          pauses.get(index).close();
        }
      }
    }
  }

  private Map<Path, Set<String>> exclusiveDirectoryPlan(String analyzerId) throws IOException {
    Map<Path, List<AnalyzerEntry>> byDirectory = new LinkedHashMap<>();
    for (var claim : connections.fileDirectoryClaims()) {
      Path directory = claim.directory().toFile().getCanonicalFile().toPath();
      AnalyzerEntry owner = new AnalyzerEntry();
      owner.setId(claim.analyzerId());
      owner.setFilePattern(claim.filePattern());
      byDirectory.computeIfAbsent(directory, ignored -> new ArrayList<>()).add(owner);
    }
    // Include active settings too: a saved edit may not have been activated yet.
    for (AnalyzerEntry entry : registry.getRegisteredAnalyzers().values()) {
      if ("HTTP".equals(entry.getInboundTransport())) continue;
      if (!"FILE".equalsIgnoreCase(entry.getExpectedProtocol())) continue;
      if (entry.getFileDirectory() == null || entry.getFileDirectory().isBlank()) {
        throw new IllegalArgumentException("Active FILE connection has no saved directory");
      }
      Path directory = Path.of(entry.getFileDirectory()).toFile().getCanonicalFile().toPath();
      byDirectory.computeIfAbsent(directory, ignored -> new ArrayList<>()).add(entry);
    }
    Map<Path, Set<String>> plan = new LinkedHashMap<>();
    for (Map.Entry<Path, List<AnalyzerEntry>> directory : byDirectory.entrySet()) {
      List<AnalyzerEntry> owners = directory.getValue();
      if (owners.stream().noneMatch(entry -> analyzerId.equals(entry.getId()))) continue;
      if (owners.stream().anyMatch(entry -> !analyzerId.equals(entry.getId()))) {
        throw new IllegalArgumentException("Cannot reset a directory shared by multiple analyzers");
      }
      Set<String> patterns = new LinkedHashSet<>();
      for (AnalyzerEntry owner : owners) {
        String pattern = owner.getFilePattern();
        if (pattern == null || pattern.isBlank()) {
          throw new IllegalArgumentException("Cannot reset without a saved file pattern");
        }
        FileSystems.getDefault().getPathMatcher("glob:" + pattern);
        patterns.add(pattern);
      }
      plan.put(directory.getKey(), patterns);
    }
    return plan;
  }

  private ResponseEntity<Map<String, Object>> resetExclusive(String analyzerId, Map<Path, Set<String>> plan) {
    Set<Path> files = new LinkedHashSet<>();
    List<String> watchDirs = plan.keySet().stream().map(Path::toString).toList();
    try {
      // Enumerate only after all directories are drained, before deleting any file.
      for (var directory : plan.entrySet()) {
        Path dir = directory.getKey();
        for (String pattern : directory.getValue()) {
          var matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
          if (!Files.isDirectory(dir)) continue;
          try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path file : stream) {
              if (
                Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && matcher.matches(file.getFileName())
              ) files.add(file);
            }
          }
        }
      }
    } catch (IOException | IllegalArgumentException exception) {
      return ResponseEntity.status(409).body(Map.of("reset", false, "error", exception.getMessage()));
    }

    int stateRowsRemoved = 0;
    int filesRemoved = 0;
    try {
      for (Path file : files) {
        if (Files.deleteIfExists(file)) filesRemoved++;
      }
      if (fileStateStore != null) {
        stateRowsRemoved = fileStateStore.deleteAllForAnalyzer(analyzerId);
      }
    } catch (IOException | RuntimeException exception) {
      log.warn("admin/reset failed for {} after removing {} files", analyzerId, filesRemoved, exception);
      return ResponseEntity.status(500).body(
        Map.of("reset", false, "error", "Reset did not complete", "filesRemoved", filesRemoved)
      );
    }

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("reset", true);
    body.put("analyzerId", analyzerId);
    body.put("stateRowsRemoved", stateRowsRemoved);
    body.put("filesRemoved", filesRemoved);
    body.put("watchDirectories", watchDirs);
    log.info(
      "admin/reset: analyzerId={} stateRowsRemoved={} filesRemoved={} watchDirs={}",
      analyzerId,
      stateRowsRemoved,
      filesRemoved,
      watchDirs
    );
    return ResponseEntity.ok(body);
  }
}
