package org.itech.ahb.file;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Durable per-file processing state for the analyzer bridge.
 * <p>
 * The bridge uses this store instead of moving or deleting files to track
 * which files have been processed, which are currently retrying, and which
 * have exhausted retries. Keyed on {@code (analyzerId, contentHash)} so that
 * identical content on different paths is deduplicated (idempotent re-drops)
 * while a new hash at an existing path is treated as a fresh observation
 * (amended / corrected exports).
 * </p>
 * <p>
 * Implementations must be durable across JVM restarts — the bridge relies on
 * the stored {@code next_attempt_at} to honor retry backoff even after crash
 * or redeploy.
 * </p>
 * <p>
 * <b>Concurrency contract:</b> a single bridge JVM per store instance. Blue/
 * green deploys must not run two bridges against the same state.db. See the
 * distro deploy runbook.
 * </p>
 */
public interface FileStateStore {

    /**
     * Look up the processing state for a given analyzer + content hash.
     *
     * @return present if a row exists, empty otherwise (i.e. new observation)
     */
    Optional<FileProcessingState> get(String analyzerId, String contentHash);

    /**
     * Mark a file as successfully processed (FHIR POST succeeded).
     * Idempotent: re-invoking for the same key updates {@code lastSeen}
     * and {@code lastPath} but leaves {@code firstSeen} and {@code attempts}
     * unchanged.
     */
    void markProcessed(String analyzerId, String contentHash, Path path);

    /**
     * Record that processing has started (or restarted) for this file.
     * Creates the row if missing, sets status to
     * {@link FileProcessingState.Status#RETRYING}, bumps {@code lastAttempt}
     * to now. Called at the top of each processing attempt.
     */
    void upsertRetrying(String analyzerId, String contentHash, Path path);

    /**
     * Increment the attempt counter and record the last error message.
     * Called after a processing failure but before the retry decision.
     *
     * @return the new attempt count
     */
    int incrementAttempts(String analyzerId, String contentHash, String errorMessage);

    /**
     * Persist the next-attempt timestamp so a JVM restart honors the
     * current backoff schedule. The rescan loop must compare this to
     * {@link Instant#now()} and skip files whose backoff has not elapsed.
     */
    void setNextAttemptAt(String analyzerId, String contentHash, Instant at);

    /**
     * Transition the row to
     * {@link FileProcessingState.Status#FAILED_NEEDS_HANDLING}. No further
     * automatic retries; the file remains in place for human inspection.
     * Invoked once the attempt count reaches the configured max.
     */
    void markFailedNeedsHandling(String analyzerId, String contentHash, Path path, String errorMessage);

    /**
     * Update {@code lastSeen} + {@code lastPath} without changing any other
     * field. Used on idempotent re-observations (rescan sees a file that's
     * already PROCESSED or FAILED_NEEDS_HANDLING) so operators can tell when
     * a dormant file is still being offered by the mount.
     */
    void touchLastSeen(String analyzerId, String contentHash, Path path);

    /**
     * List rows by status for admin / diagnostic queries. Ordered by
     * {@code lastSeen} descending.
     *
     * @param status   status to filter on
     * @param limit    maximum rows to return
     * @param offset   rows to skip (for paging)
     */
    List<FileProcessingState> list(FileProcessingState.Status status, int limit, int offset);
}
