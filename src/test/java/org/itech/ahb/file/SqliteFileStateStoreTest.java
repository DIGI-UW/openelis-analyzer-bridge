package org.itech.ahb.file;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link SqliteFileStateStore}.
 * <p>
 * Focus areas:
 * <ol>
 *   <li>Basic CRUD — insert / get / update / list</li>
 *   <li>Idempotency of markProcessed on re-observation</li>
 *   <li>Durability across open/close/reopen cycles (simulates JVM restart)</li>
 *   <li>Corruption recovery: a garbage-bytes file at the db path is renamed
 *       and a fresh empty store is created</li>
 *   <li>next_attempt_at persistence so retry backoff survives restart</li>
 *   <li>FAILED_NEEDS_HANDLING terminal state — no implicit retries</li>
 * </ol>
 */
class SqliteFileStateStoreTest {

    @Test
    void newStore_getOnMissingKey_returnsEmpty(@TempDir Path tmp) {
        SqliteFileStateStore store = new SqliteFileStateStore(tmp.resolve("state.db"));
        try {
            Optional<FileProcessingState> found = store.get("analyzer-1", "deadbeef");
            assertTrue(found.isEmpty());
        } finally {
            store.close();
        }
    }

    @Test
    void upsertRetrying_thenMarkProcessed_rowIsProcessedWithBumpedLastSeen(@TempDir Path tmp) {
        SqliteFileStateStore store = new SqliteFileStateStore(tmp.resolve("state.db"));
        try {
            Path file = tmp.resolve("watched/abc.xlsx");
            store.upsertRetrying("quantstudio5", "hash-a", file);
            Optional<FileProcessingState> mid = store.get("quantstudio5", "hash-a");
            assertTrue(mid.isPresent());
            assertEquals(FileProcessingState.Status.RETRYING, mid.get().status());
            assertEquals(file.toAbsolutePath().toString(), mid.get().lastPath());
            assertEquals(0, mid.get().attempts());

            store.markProcessed("quantstudio5", "hash-a", file);
            Optional<FileProcessingState> done = store.get("quantstudio5", "hash-a");
            assertTrue(done.isPresent());
            assertEquals(FileProcessingState.Status.PROCESSED, done.get().status());
            // firstSeen preserved across transitions
            assertEquals(mid.get().firstSeen(), done.get().firstSeen());
            // lastSeen advanced (or equal, within clock skew) after markProcessed
            assertFalse(done.get().lastSeen().isBefore(mid.get().lastSeen()));
        } finally {
            store.close();
        }
    }

    @Test
    void markProcessed_reObservationOfSameContent_isIdempotent(@TempDir Path tmp) {
        SqliteFileStateStore store = new SqliteFileStateStore(tmp.resolve("state.db"));
        try {
            Path file = tmp.resolve("watched/abc.xlsx");
            store.markProcessed("qs7", "hash-b", file);
            FileProcessingState first = store.get("qs7", "hash-b").orElseThrow();
            // Second pass — simulates a re-drop of the same content
            store.markProcessed("qs7", "hash-b", file);
            FileProcessingState second = store.get("qs7", "hash-b").orElseThrow();
            assertEquals(first.firstSeen(), second.firstSeen(), "firstSeen must be preserved on re-observation");
            assertEquals(FileProcessingState.Status.PROCESSED, second.status());
            // attempts should not increase on a successful re-observation
            assertEquals(first.attempts(), second.attempts());
        } finally {
            store.close();
        }
    }

    @Test
    void incrementAttempts_bumpsCounterAndReturnsNewValue(@TempDir Path tmp) {
        SqliteFileStateStore store = new SqliteFileStateStore(tmp.resolve("state.db"));
        try {
            Path file = tmp.resolve("watched/bad.xlsx");
            store.upsertRetrying("qs5", "hash-c", file);
            int a1 = store.incrementAttempts("qs5", "hash-c", "boom 1");
            int a2 = store.incrementAttempts("qs5", "hash-c", "boom 2");
            int a3 = store.incrementAttempts("qs5", "hash-c", "boom 3");
            assertEquals(1, a1);
            assertEquals(2, a2);
            assertEquals(3, a3);
            assertEquals("boom 3", store.get("qs5", "hash-c").orElseThrow().lastError());
        } finally {
            store.close();
        }
    }

    @Test
    void markFailedNeedsHandling_stampsStatusAndClearsNextAttempt(@TempDir Path tmp) {
        SqliteFileStateStore store = new SqliteFileStateStore(tmp.resolve("state.db"));
        try {
            Path file = tmp.resolve("watched/bad.xlsx");
            store.upsertRetrying("qs5", "hash-d", file);
            store.setNextAttemptAt("qs5", "hash-d", Instant.now().plusSeconds(60));
            store.markFailedNeedsHandling("qs5", "hash-d", file, "EmptyResults");
            FileProcessingState row = store.get("qs5", "hash-d").orElseThrow();
            assertEquals(FileProcessingState.Status.FAILED_NEEDS_HANDLING, row.status());
            assertEquals("EmptyResults", row.lastError());
            assertNull(row.nextAttemptAt(), "next_attempt_at must be cleared once we give up retrying");
        } finally {
            store.close();
        }
    }

    @Test
    void setNextAttemptAt_persistedAcrossReopen(@TempDir Path tmp) {
        Path db = tmp.resolve("state.db");
        Instant target = Instant.now().plus(30, ChronoUnit.SECONDS);

        SqliteFileStateStore first = new SqliteFileStateStore(db);
        try {
            Path file = tmp.resolve("watched/later.csv");
            first.upsertRetrying("qs7", "hash-e", file);
            first.setNextAttemptAt("qs7", "hash-e", target);
        } finally {
            first.close();
        }

        SqliteFileStateStore second = new SqliteFileStateStore(db);
        try {
            FileProcessingState row = second.get("qs7", "hash-e").orElseThrow();
            assertNotNull(row.nextAttemptAt());
            // ISO_INSTANT formatter truncates sub-microsecond precision on some
            // platforms — compare by epoch-second for robustness.
            assertEquals(target.getEpochSecond(), row.nextAttemptAt().getEpochSecond());
            assertEquals(FileProcessingState.Status.RETRYING, row.status());
        } finally {
            second.close();
        }
    }

    @Test
    void listByStatus_respectsFilterAndOrder(@TempDir Path tmp) {
        SqliteFileStateStore store = new SqliteFileStateStore(tmp.resolve("state.db"));
        try {
            store.markProcessed("qs5", "h1", tmp.resolve("one.xls"));
            store.markProcessed("qs5", "h2", tmp.resolve("two.xls"));
            store.upsertRetrying("qs5", "h3", tmp.resolve("three.xls"));
            store.markFailedNeedsHandling("qs5", "h4", tmp.resolve("four.xls"), "bad");
            // h4 was marked FAILED from a row that never existed — set it up first
            store.upsertRetrying("qs5", "h4b", tmp.resolve("four-b.xls"));
            store.markFailedNeedsHandling("qs5", "h4b", tmp.resolve("four-b.xls"), "bad");

            List<FileProcessingState> processed =
                    store.list(FileProcessingState.Status.PROCESSED, 10, 0);
            assertEquals(2, processed.size());
            for (FileProcessingState p : processed) {
                assertEquals(FileProcessingState.Status.PROCESSED, p.status());
            }

            List<FileProcessingState> retrying =
                    store.list(FileProcessingState.Status.RETRYING, 10, 0);
            assertEquals(1, retrying.size());
            assertEquals("h3", retrying.get(0).contentHash());

            List<FileProcessingState> failed =
                    store.list(FileProcessingState.Status.FAILED_NEEDS_HANDLING, 10, 0);
            assertEquals(1, failed.size(), "only h4b was correctly transitioned to FAILED");
            assertEquals("h4b", failed.get(0).contentHash());
        } finally {
            store.close();
        }
    }

    @Test
    void reopen_preservesAllRows(@TempDir Path tmp) {
        Path db = tmp.resolve("state.db");

        SqliteFileStateStore first = new SqliteFileStateStore(db);
        try {
            first.markProcessed("qs5", "h1", tmp.resolve("a.xls"));
            first.markProcessed("qs7", "h2", tmp.resolve("b.xls"));
            first.upsertRetrying("tecan", "h3", tmp.resolve("c.xlsx"));
            first.incrementAttempts("tecan", "h3", "first failure");
        } finally {
            first.close();
        }

        SqliteFileStateStore second = new SqliteFileStateStore(db);
        try {
            assertEquals(FileProcessingState.Status.PROCESSED,
                    second.get("qs5", "h1").orElseThrow().status());
            assertEquals(FileProcessingState.Status.PROCESSED,
                    second.get("qs7", "h2").orElseThrow().status());
            FileProcessingState retrying = second.get("tecan", "h3").orElseThrow();
            assertEquals(FileProcessingState.Status.RETRYING, retrying.status());
            assertEquals(1, retrying.attempts());
            assertEquals("first failure", retrying.lastError());
        } finally {
            second.close();
        }
    }

    @Test
    void corruptDbFile_isRenamedAndReplacedWithFreshStore(@TempDir Path tmp) throws Exception {
        Path db = tmp.resolve("state.db");
        // Plant garbage bytes that are NOT a valid SQLite header
        Files.write(db, "this is definitely not a sqlite database file".getBytes());
        byte[] originalBytes = Files.readAllBytes(db);

        SqliteFileStateStore store = new SqliteFileStateStore(db);
        try {
            // Store must be operational after corruption recovery
            store.markProcessed("qs5", "fresh", tmp.resolve("x.xls"));
            assertTrue(store.get("qs5", "fresh").isPresent());
        } finally {
            store.close();
        }

        // The original garbage file must have been renamed to state.db.corrupt-<ts>
        try (var entries = Files.list(tmp)) {
            boolean foundRename = entries
                    .map(p -> p.getFileName().toString())
                    .anyMatch(n -> n.startsWith("state.db.corrupt-"));
            assertTrue(foundRename, "corrupted db should be renamed with .corrupt- suffix");
        }

        // The new state.db at the original path is a real SQLite file, not the garbage bytes
        byte[] newBytes = Files.readAllBytes(db);
        assertNotEquals(
                new String(originalBytes).substring(0, 16),
                new String(newBytes, 0, Math.min(16, newBytes.length)),
                "a fresh SQLite file must replace the garbage bytes");
        assertTrue(newBytes.length > 0);
    }

    @Test
    void touchLastSeen_doesNotDisturbStatusOrAttempts(@TempDir Path tmp) {
        SqliteFileStateStore store = new SqliteFileStateStore(tmp.resolve("state.db"));
        try {
            Path file = tmp.resolve("watched/steady.xlsx");
            store.upsertRetrying("qs5", "h1", file);
            store.incrementAttempts("qs5", "h1", "first");
            store.markProcessed("qs5", "h1", file);
            FileProcessingState before = store.get("qs5", "h1").orElseThrow();

            store.touchLastSeen("qs5", "h1", file);
            FileProcessingState after = store.get("qs5", "h1").orElseThrow();
            assertEquals(before.status(), after.status());
            assertEquals(before.attempts(), after.attempts());
            assertFalse(after.lastSeen().isBefore(before.lastSeen()));
        } finally {
            store.close();
        }
    }

    // --- rejected_bundles (B1) -----------------------------------------

    @Test
    void recordRejection_roundTripsThroughListRejections(@TempDir Path tmp) {
        SqliteFileStateStore store = new SqliteFileStateStore(tmp.resolve("state.db"));
        try {
            String payload = "{\"resourceType\":\"Bundle\",\"type\":\"transaction\"}";
            String id = store.recordRejection("100.127.144.150", "ASTM", 401,
                    "HTTP 401 Unauthorized", payload);
            assertNotNull(id);

            List<RejectedBundle> rows = store.listRejections(100);
            assertEquals(1, rows.size());
            RejectedBundle r = rows.get(0);
            assertEquals(id, r.id());
            assertEquals("100.127.144.150", r.sourceId());
            assertEquals("ASTM", r.protocol());
            assertEquals(401, r.httpStatus());
            assertEquals(payload, r.payloadSnippet(),
                    "small payload must round-trip in snippet unchanged");
            assertNotNull(r.payloadHash());
            assertEquals(64, r.payloadHash().length(), "SHA-256 hex is 64 chars");
            assertFalse(r.dismissed());
            assertNotNull(r.rejectedAt());
        } finally {
            store.close();
        }
    }

    @Test
    void recordRejection_clampsHugePayloadSnippet(@TempDir Path tmp) {
        SqliteFileStateStore store = new SqliteFileStateStore(tmp.resolve("state.db"));
        try {
            String huge = "x".repeat(100_000);
            store.recordRejection("src", "FHIR", 422, "schema", huge);
            RejectedBundle r = store.listRejections(1).get(0);
            assertEquals(800, r.payloadSnippet().length(),
                    "snippet must be clamped to PAYLOAD_SNIPPET_MAX");
            // Hash must be over the full payload, not the snippet
            assertNotNull(r.payloadHash());
        } finally {
            store.close();
        }
    }

    @Test
    void dismissRejection_hidesFromActiveListButRowStillExists(@TempDir Path tmp) {
        SqliteFileStateStore store = new SqliteFileStateStore(tmp.resolve("state.db"));
        try {
            String id = store.recordRejection("gx1", "ASTM", 500, "boom", "msg");
            assertEquals(1, store.listRejections(100).size());

            assertTrue(store.dismissRejection(id));
            assertEquals(0, store.listRejections(100).size(),
                    "dismissed row must be hidden from active list");

            // Idempotency: second dismiss is a no-op
            assertFalse(store.dismissRejection(id),
                    "already-dismissed id returns false so the REST endpoint can 404");
            assertFalse(store.dismissRejection("does-not-exist"));
        } finally {
            store.close();
        }
    }

    @Test
    void listRejections_orderedByNewestFirst(@TempDir Path tmp) throws InterruptedException {
        SqliteFileStateStore store = new SqliteFileStateStore(tmp.resolve("state.db"));
        try {
            String first = store.recordRejection("src", "ASTM", 500, "a", "1");
            Thread.sleep(15); // ISO_INSTANT has ms resolution
            String second = store.recordRejection("src", "ASTM", 500, "b", "2");
            Thread.sleep(15);
            String third = store.recordRejection("src", "ASTM", 500, "c", "3");

            List<RejectedBundle> rows = store.listRejections(100);
            assertEquals(3, rows.size());
            assertEquals(third, rows.get(0).id());
            assertEquals(second, rows.get(1).id());
            assertEquals(first, rows.get(2).id());
        } finally {
            store.close();
        }
    }

    @Test
    void rejectedBundles_persistAcrossReopen(@TempDir Path tmp) {
        Path dbPath = tmp.resolve("state.db");
        String id;
        {
            SqliteFileStateStore store = new SqliteFileStateStore(dbPath);
            try {
                id = store.recordRejection("src", "FHIR", 401, "auth", "payload");
            } finally {
                store.close();
            }
        }
        // Simulate JVM restart by opening the same file in a new store
        SqliteFileStateStore reopened = new SqliteFileStateStore(dbPath);
        try {
            List<RejectedBundle> rows = reopened.listRejections(10);
            assertEquals(1, rows.size());
            assertEquals(id, rows.get(0).id());
        } finally {
            reopened.close();
        }
    }
}
