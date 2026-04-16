package org.itech.ahb.controller;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.itech.ahb.file.RejectedBundle;
import org.itech.ahb.file.SqliteFileStateStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only admin endpoint for inspecting bundles that the bridge attempted
 * to forward but OE rejected. Paired with {@link org.itech.ahb.routing.HttpForwardingRouter}
 * which records an entry on:
 * <ul>
 *   <li>Non-retryable 4xx (auth failure, bad FHIR bundle, etc.)</li>
 *   <li>Retry exhaustion (5xx/IO for every attempt)</li>
 * </ul>
 * Operators read this endpoint to answer "where did that GeneXpert result
 * go?" when no staging row appears in OE. OE's Import Issues dashboard (A4)
 * aggregates this endpoint with its own orphan-staging query.
 * <p>
 * Protected by the bridge's Spring Security config (same path-matcher as
 * {@code /admin/file-state}).
 * </p>
 */
@RestController
@RequestMapping("/admin/rejected-bundles")
@Slf4j
public class RejectedBundlesController {

    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 1000;

    private final SqliteFileStateStore stateStore;

    public RejectedBundlesController(@Autowired(required = false) SqliteFileStateStore stateStore) {
        this.stateStore = stateStore;
    }

    /**
     * List active (non-dismissed) rejected bundles ordered by most-recent
     * rejection first.
     */
    @GetMapping
    public ResponseEntity<?> list(
            @RequestParam(required = false, defaultValue = "100") Integer limit) {
        if (stateStore == null) {
            return ResponseEntity.status(503).body(Map.of(
                    "error", "state_store_unavailable",
                    "message", "SqliteFileStateStore not configured (bridge.file.enabled=false?)"));
        }
        int safeLimit = Math.max(1, Math.min(limit == null ? DEFAULT_LIMIT : limit, MAX_LIMIT));
        List<RejectedBundle> rows = stateStore.listRejections(safeLimit);
        List<Map<String, Object>> out = rows.stream().map(RejectedBundlesController::toJson).toList();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("limit", safeLimit);
        body.put("count", out.size());
        body.put("rows", out);
        return ResponseEntity.ok(body);
    }

    /**
     * Mark a rejected bundle as dismissed — it stays in the table for audit
     * but is hidden from the default list. No hard delete so that a later
     * postmortem can still reach it.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> dismiss(@PathVariable String id) {
        if (stateStore == null) {
            return ResponseEntity.status(503).body(Map.of(
                    "error", "state_store_unavailable"));
        }
        boolean updated = stateStore.dismissRejection(id);
        if (!updated) {
            return ResponseEntity.status(404).body(Map.of(
                    "error", "not_found_or_already_dismissed",
                    "id", id));
        }
        log.info("Dismissed rejected bundle id={}", id);
        return ResponseEntity.ok(Map.of("id", id, "dismissed", true));
    }

    private static Map<String, Object> toJson(RejectedBundle b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", b.id());
        m.put("sourceId", b.sourceId());
        m.put("protocol", b.protocol());
        m.put("httpStatus", b.httpStatus());
        m.put("lastError", b.lastError());
        m.put("payloadHash", b.payloadHash());
        m.put("payloadSnippet", b.payloadSnippet());
        m.put("rejectedAt", formatTs(b.rejectedAt()));
        m.put("dismissed", b.dismissed());
        return m;
    }

    private static String formatTs(Instant ts) {
        return ts == null ? null : ts.toString();
    }
}
