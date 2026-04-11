package org.itech.ahb.controller;

import lombok.extern.slf4j.Slf4j;
import org.itech.ahb.file.FileProcessingState;
import org.itech.ahb.file.FileStateStore;
import org.itech.ahb.file.FileWatcher;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Read-only admin endpoint for inspecting the bridge's file processing state.
 * <p>
 * Operators use this to answer questions like:
 * <ul>
 *   <li>"What files did the bridge fail to parse on mgtest?"</li>
 *   <li>"Was this specific file actually processed, and if so when?"</li>
 *   <li>"Are there any files still retrying and when will they next attempt?"</li>
 * </ul>
 * Previously these questions required SSH + {@code ls archive/} + {@code ls error/};
 * now they're answered by querying the {@link FileStateStore} SQLite database
 * that replaced those directories. Plan mellow-honking-cascade Phase 1.7.
 * </p>
 * <p>
 * This controller is protected by the bridge's existing Spring Security config
 * — the same credentials that allow access to {@code /api/query},
 * {@code /api/register}, etc. apply here.
 * </p>
 */
@RestController
@RequestMapping("/admin/file-state")
@Slf4j
public class FileStateController {

    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 1000;

    private final FileWatcher fileWatcher;

    public FileStateController(FileWatcher fileWatcher) {
        this.fileWatcher = fileWatcher;
    }

    /**
     * List file state rows filtered by status, ordered by last_seen descending.
     *
     * @param status one of {@code PROCESSED}, {@code FAILED_NEEDS_HANDLING},
     *               {@code RETRYING}
     * @param limit  max rows to return (default 100, max 1000)
     * @param offset rows to skip (for paging, default 0)
     */
    @GetMapping
    public ResponseEntity<?> listByStatus(
            @RequestParam String status,
            @RequestParam(required = false, defaultValue = "100") Integer limit,
            @RequestParam(required = false, defaultValue = "0") Integer offset) {

        FileStateStore store = fileWatcher.getStateStore();
        if (store == null) {
            return ResponseEntity.status(503).body(Map.of(
                    "error", "file_state_store_unavailable",
                    "message", "FileWatcher has not been initialized yet"));
        }

        FileProcessingState.Status parsed;
        try {
            parsed = FileProcessingState.Status.valueOf(status.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "invalid_status",
                    "message", "status must be one of PROCESSED, FAILED_NEEDS_HANDLING, RETRYING"));
        }

        int safeLimit = Math.max(1, Math.min(limit == null ? DEFAULT_LIMIT : limit, MAX_LIMIT));
        int safeOffset = Math.max(0, offset == null ? 0 : offset);

        List<FileProcessingState> rows = store.list(parsed, safeLimit, safeOffset);
        List<Map<String, Object>> out = rows.stream().map(FileStateController::toJson).toList();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", parsed.name());
        body.put("limit", safeLimit);
        body.put("offset", safeOffset);
        body.put("count", out.size());
        body.put("rows", out);
        return ResponseEntity.ok(body);
    }

    /**
     * Look up a single file state row by its primary key.
     * Used by E2E tests to assert that the bridge processed a specific
     * dropped fixture (Phase 4 of the plan).
     */
    @GetMapping("/{analyzerId}/{contentHash}")
    public ResponseEntity<?> getOne(
            @PathVariable String analyzerId,
            @PathVariable String contentHash) {

        FileStateStore store = fileWatcher.getStateStore();
        if (store == null) {
            return ResponseEntity.status(503).body(Map.of(
                    "error", "file_state_store_unavailable"));
        }

        Optional<FileProcessingState> row = store.get(analyzerId, contentHash);
        if (row.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of(
                    "error", "not_found",
                    "analyzerId", analyzerId,
                    "contentHash", contentHash));
        }
        return ResponseEntity.ok(toJson(row.get()));
    }

    private static Map<String, Object> toJson(FileProcessingState s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("analyzerId", s.analyzerId());
        m.put("contentHash", s.contentHash());
        m.put("status", s.status().name());
        m.put("lastPath", s.lastPath());
        m.put("firstSeen", formatTs(s.firstSeen()));
        m.put("lastSeen", formatTs(s.lastSeen()));
        m.put("lastAttempt", formatTs(s.lastAttempt()));
        m.put("nextAttemptAt", formatTs(s.nextAttemptAt()));
        m.put("attempts", s.attempts());
        m.put("lastError", s.lastError());
        return m;
    }

    private static String formatTs(Instant ts) {
        return ts == null ? null : ts.toString();
    }
}
