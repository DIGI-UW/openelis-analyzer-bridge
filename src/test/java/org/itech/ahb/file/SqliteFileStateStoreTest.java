package org.itech.ahb.file;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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

    /**
     * Concurrent access contract tests.
     *
     * <p>The store is held by FileWatcher (2-thread processor pool + scheduled
     * retry tasks) and the admin REST controller simultaneously. JDBC {@link
     * java.sql.Connection} is not guaranteed to be thread-safe by the JDBC
     * specification; explicit synchronization on the store's public methods
     * makes the contract independent of driver-specific behavior.
     *
     * <p><b>Important caveat:</b> these tests do not fail against an
     * un-{@code synchronized} version of the production code because the
     * current driver ({@code org.xerial:sqlite-jdbc 3.46.0.0}) synchronizes
     * internally at the native-connection level. They exist as:
     *
     * <ol>
     *   <li>A contract test — "the store supports concurrent access without
     *       throwing, and the attempts counter reflects every increment".</li>
     *   <li>A regression guard against future driver swaps, connection
     *       pooling wrappers, or a change that spreads DB access across
     *       multiple {@link java.sql.Connection} instances without external
     *       synchronization.</li>
     * </ol>
     *
     * <p>In other words: these tests document the invariant the explicit
     * {@code synchronized} modifier enforces. They do not prove it.
     */
    @org.junit.jupiter.api.Nested
    @org.junit.jupiter.api.DisplayName("Concurrent access contract")
    class Concurrency {

        @Test
        void manyThreads_mixedOps_noExceptionsAndConsistentState(@TempDir Path tmp) throws Exception {
            SqliteFileStateStore store = new SqliteFileStateStore(tmp.resolve("state.db"));
            try {
                final int threads = 8;
                final int opsPerThread = 50;
                ExecutorService pool = Executors.newFixedThreadPool(threads);
                CountDownLatch start = new CountDownLatch(1);
                CountDownLatch done = new CountDownLatch(threads);
                AtomicReference<Throwable> firstError = new AtomicReference<>();

                // Seed one shared row every thread will touch, plus one row per thread.
                Path sharedFile = tmp.resolve("shared.xlsx");
                store.upsertRetrying("shared", "hash-shared", sharedFile);

                for (int t = 0; t < threads; t++) {
                    final int threadIdx = t;
                    pool.submit(() -> {
                        try {
                            start.await();
                            Path myFile = tmp.resolve("t" + threadIdx + ".xlsx");
                            for (int i = 0; i < opsPerThread; i++) {
                                switch (i % 5) {
                                    case 0 -> store.upsertRetrying("t" + threadIdx, "h-" + threadIdx, myFile);
                                    case 1 -> store.incrementAttempts("shared", "hash-shared",
                                            "err from t" + threadIdx + "-" + i);
                                    case 2 -> store.touchLastSeen("shared", "hash-shared", sharedFile);
                                    case 3 -> store.get("t" + threadIdx, "h-" + threadIdx);
                                    case 4 -> store.list(FileProcessingState.Status.RETRYING, 50, 0);
                                }
                            }
                        } catch (Throwable e) {
                            firstError.compareAndSet(null, e);
                        } finally {
                            done.countDown();
                        }
                    });
                }

                start.countDown();
                assertTrue(done.await(30, TimeUnit.SECONDS),
                        "concurrent workload should complete within 30s");
                pool.shutdown();
                assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));

                assertNull(firstError.get(),
                        "no thread should throw during concurrent access; got: " + firstError.get());

                // Every thread incrementAttempts ran opsPerThread/5 = 10 times → 8*10 = 80 increments
                // on the shared row. Attempts counter must equal 80 exactly (no lost updates).
                int expectedAttempts = threads * (opsPerThread / 5);
                FileProcessingState shared = store.get("shared", "hash-shared").orElseThrow();
                assertEquals(expectedAttempts, shared.attempts(),
                        "attempts counter must reflect every increment — a lost update means the "
                                + "increment wasn't serialized");

                // Each thread-specific row exists and is RETRYING.
                for (int t = 0; t < threads; t++) {
                    FileProcessingState row = store.get("t" + t, "h-" + t).orElseThrow();
                    assertEquals(FileProcessingState.Status.RETRYING, row.status());
                }
            } finally {
                store.close();
            }
        }

        @Test
        void reader_concurrentWithWriter_neverThrowsOrReturnsGarbage(@TempDir Path tmp) throws Exception {
            // Simulates the admin REST controller list() racing against FileWatcher
            // writes. The admin path should never throw SQLException and should
            // always return a valid snapshot (status enum parseable, attempts >= 0).
            SqliteFileStateStore store = new SqliteFileStateStore(tmp.resolve("state.db"));
            try {
                AtomicInteger writes = new AtomicInteger();
                AtomicInteger reads = new AtomicInteger();
                AtomicReference<Throwable> firstError = new AtomicReference<>();
                long deadline = System.currentTimeMillis() + 2_000;

                Thread writer = new Thread(() -> {
                    try {
                        int i = 0;
                        while (System.currentTimeMillis() < deadline) {
                            Path p = tmp.resolve("w" + (i % 20) + ".xlsx");
                            store.upsertRetrying("analyzer-w", "hash-" + (i % 20), p);
                            store.incrementAttempts("analyzer-w", "hash-" + (i % 20), "n=" + i);
                            if (i % 3 == 0) {
                                store.markProcessed("analyzer-w", "hash-" + (i % 20), p);
                            }
                            writes.incrementAndGet();
                            i++;
                        }
                    } catch (Throwable e) {
                        firstError.compareAndSet(null, e);
                    }
                }, "writer");

                Thread reader = new Thread(() -> {
                    try {
                        while (System.currentTimeMillis() < deadline) {
                            List<FileProcessingState> rows = store.list(
                                    FileProcessingState.Status.RETRYING, 100, 0);
                            for (FileProcessingState r : rows) {
                                // Accessing all fields forces full row materialization — would
                                // expose a half-read row (null fields, ClassCast on the status
                                // column) if the Connection were mid-mutation under us.
                                assertNotNull(r.status());
                                assertNotNull(r.analyzerId());
                                assertNotNull(r.contentHash());
                                assertTrue(r.attempts() >= 0);
                            }
                            reads.incrementAndGet();
                        }
                    } catch (Throwable e) {
                        firstError.compareAndSet(null, e);
                    }
                }, "reader");

                writer.start();
                reader.start();
                writer.join(10_000);
                reader.join(10_000);

                assertNull(firstError.get(),
                        "neither reader nor writer should throw; got: " + firstError.get());
                assertTrue(writes.get() > 0, "writer should have made progress");
                assertTrue(reads.get() > 0, "reader should have made progress");
            } finally {
                store.close();
            }
        }
    }

    /**
     * Unit tests for {@link SqliteFileStateStore#isCorruptionException(SQLException)}.
     *
     * <p>Why these tests matter: before the fix, {@code openOrRecover} caught
     * <em>any</em> SQLException and immediately renamed the db file to
     * {@code .corrupt-<timestamp>}, then created a fresh store. That's
     * catastrophic for transient errors (missing JDBC driver at classpath
     * resolution time, filesystem permission denied, disk full) because it
     * destroys a potentially-healthy database in response to an
     * operator-fixable condition. The classifier restricts the destructive
     * rename path to actual corruption signals.
     *
     * <p>Testing the classifier directly (rather than simulating end-to-end
     * corruption scenarios) is more robust: simulating a permission-denied
     * SQLite open on macOS, Windows, and Linux uniformly is fragile, whereas
     * constructing synthetic {@link SQLException}s with specific error codes
     * and messages is deterministic and platform-independent.
     */
    @org.junit.jupiter.api.Nested
    @org.junit.jupiter.api.DisplayName("Corruption classifier — only rename-and-replace for real corruption")
    class CorruptionClassifier {

        @org.junit.jupiter.api.Test
        void sqliteCorruptCode_classifiedAsCorrupt() {
            SQLException e = new SQLException("database disk image is malformed", "SQLITE_CORRUPT", 11);
            assertTrue(SqliteFileStateStore.isCorruptionException(e),
                    "SQLITE_CORRUPT (code 11) must be treated as corruption");
        }

        @org.junit.jupiter.api.Test
        void sqliteNotADbCode_classifiedAsCorrupt() {
            SQLException e = new SQLException("file is not a database", "SQLITE_NOTADB", 26);
            assertTrue(SqliteFileStateStore.isCorruptionException(e),
                    "SQLITE_NOTADB (code 26) must be treated as corruption");
        }

        @org.junit.jupiter.api.Test
        void integrityCheckFailedMessage_classifiedAsCorrupt() {
            // openWithPragmas throws a synthetic SQLException with no error code
            // when PRAGMA integrity_check reports a problem. The classifier must
            // match on message text as a fallback.
            SQLException e = new SQLException("Integrity check failed: wrong # of entries in index");
            assertTrue(SqliteFileStateStore.isCorruptionException(e),
                    "synthetic integrity-check failure must be treated as corruption");
        }

        @org.junit.jupiter.api.Test
        void permissionDeniedError_notCorrupt() {
            // SQLITE_PERM (3): filesystem permission denied. Not corruption —
            // operator needs to chmod, not the DB renamed.
            SQLException e = new SQLException("unable to open database file", "SQLITE_PERM", 14);
            assertFalse(SqliteFileStateStore.isCorruptionException(e),
                    "permission-denied (code 14) must NOT trigger the destructive rename path");
        }

        @org.junit.jupiter.api.Test
        void busyLockError_notCorrupt() {
            // SQLITE_BUSY (5): another process/thread holds the lock. Transient.
            SQLException e = new SQLException("database is locked", "SQLITE_BUSY", 5);
            assertFalse(SqliteFileStateStore.isCorruptionException(e),
                    "busy lock (code 5) is transient, must NOT trigger rename");
        }

        @org.junit.jupiter.api.Test
        void missingDriverError_notCorrupt() {
            // Simulates ClassNotFoundException wrapped in SQLException — though
            // usually surfaces as the unchecked variant, message-level rejection
            // covers the common forms.
            SQLException e = new SQLException("No suitable driver found for jdbc:sqlite:/tmp/x.db");
            assertFalse(SqliteFileStateStore.isCorruptionException(e),
                    "missing-driver error message must NOT trigger rename");
        }

        @org.junit.jupiter.api.Test
        void unrelatedSqlException_notCorrupt() {
            SQLException e = new SQLException("some unrelated failure", "XX000", 999);
            assertFalse(SqliteFileStateStore.isCorruptionException(e),
                    "unknown error must default to 'not corruption' (safe default)");
        }

        @org.junit.jupiter.api.Test
        void corruptionInCauseChain_stillClassified() {
            // Corruption sometimes arrives wrapped in an outer driver exception.
            SQLException root = new SQLException("database disk image is malformed", "SQLITE_CORRUPT", 11);
            SQLException wrapper = new SQLException("wrapper", "XX000", 0);
            wrapper.initCause(root);
            assertTrue(SqliteFileStateStore.isCorruptionException(wrapper),
                    "corruption exception wrapped in a cause chain must still classify as corruption");
        }
    }
}
