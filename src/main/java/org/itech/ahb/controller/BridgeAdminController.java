package org.itech.ahb.controller;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.itech.ahb.connection.AnalyzerRuntimeRegistry;
import org.itech.ahb.connection.AnalyzerRuntimeRegistry.AnalyzerEntry;
import org.itech.ahb.file.FileStateStore;
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
 * accumulating in the watch directory and racing with new uploads on the
 * SQLite {@code (analyzer_id, content_hash)} row.
 */
@RestController
@RequestMapping("/admin")
@Slf4j
public class BridgeAdminController {

    private final AnalyzerRuntimeRegistry registry;
    private final FileStateStore fileStateStore;

    // FileStateStore only exists when file handling is enabled
    // (StateStoreConfig is @ConditionalOnProperty bridge.file.enabled). The
    // admin controller must still load (and serve watch-dir cleanup) when
    // file mode is off, so the store is an optional dependency — null then,
    // and the SQLite-row reset below is simply skipped.
    public BridgeAdminController(AnalyzerRuntimeRegistry registry, @Nullable FileStateStore fileStateStore) {
        this.registry = registry;
        this.fileStateStore = fileStateStore;
    }

    /**
     * Reset bridge-side state for a single analyzer:
     * <ol>
     *   <li>Delete all rows in the SQLite file_state for {@code analyzerId}</li>
     *   <li>Delete every file in that analyzer's registered watch directory(ies)
     *       (FILE protocol only — TCP analyzers have nothing to clean)</li>
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
            return ResponseEntity.badRequest().body(Map.of(
                    "reset", false,
                    "error", "analyzerId is required"));
        }

        int stateRowsRemoved = 0;
        if (fileStateStore != null) {
            try {
                stateRowsRemoved = fileStateStore.deleteAllForAnalyzer(analyzerId);
            } catch (RuntimeException e) {
                log.warn("admin/reset: deleteAllForAnalyzer failed for {}: {}", analyzerId, e.getMessage());
            }
        }

        int filesRemoved = 0;
        java.util.List<String> watchDirs = new java.util.ArrayList<>();
        // FILE registry keys append the durable connection id so two connections can
        // share a directory without replacing each other. Strip that exact suffix
        // before performing filesystem operations.
        for (Map.Entry<String, AnalyzerEntry> e : registry.getRegisteredAnalyzers().entrySet()) {
            AnalyzerEntry entry = e.getValue();
            if (!analyzerId.equals(entry.getId())) continue;
            String protocol = entry.getExpectedProtocol();
            if (protocol == null || (!protocol.equalsIgnoreCase("FILE") && !protocol.equalsIgnoreCase("CSV"))) {
                continue;
            }
            Path dir = watchDirectory(e.getKey(), entry);
            watchDirs.add(dir.toString());
            if (!Files.isDirectory(dir)) continue;
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
                for (Path p : stream) {
                    if (Files.isRegularFile(p)) {
                        try {
                            Files.deleteIfExists(p);
                            filesRemoved++;
                        } catch (IOException ioe) {
                            log.warn("admin/reset: failed to delete {}: {}", p, ioe.getMessage());
                        }
                    }
                }
            } catch (IOException ioe) {
                log.warn("admin/reset: failed to list {}: {}", dir, ioe.getMessage());
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reset", true);
        body.put("analyzerId", analyzerId);
        body.put("stateRowsRemoved", stateRowsRemoved);
        body.put("filesRemoved", filesRemoved);
        body.put("watchDirectories", watchDirs);
        log.info("admin/reset: analyzerId={} stateRowsRemoved={} filesRemoved={} watchDirs={}",
                analyzerId, stateRowsRemoved, filesRemoved, watchDirs);
        return ResponseEntity.ok(body);
    }

    private Path watchDirectory(String registryKey, AnalyzerEntry entry) {
        String connectionId = entry.getBridgeConnectionId();
        if (connectionId != null && !connectionId.isBlank()) {
            String suffix = "#" + connectionId;
            if (registryKey.endsWith(suffix)) {
                return Path.of(registryKey.substring(0, registryKey.length() - suffix.length()));
            }
        }
        return Path.of(registryKey);
    }
}
