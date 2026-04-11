package org.itech.ahb.file;

import java.time.Instant;

/**
 * Immutable record of a single file's processing state as seen by the bridge.
 * <p>
 * Stored in {@link FileStateStore}, keyed on {@code (analyzerId, contentHash)}.
 * Files are never deleted or moved by the bridge — all state transitions
 * (new → RETRYING → PROCESSED, or new → RETRYING → FAILED_NEEDS_HANDLING)
 * are recorded here as metadata so the bridge can keep the watched directory
 * strictly read-only.
 * </p>
 */
public record FileProcessingState(
        String analyzerId,
        String contentHash,
        Status status,
        String lastPath,
        Instant firstSeen,
        Instant lastSeen,
        Instant lastAttempt,
        Instant nextAttemptAt,
        int attempts,
        String lastError
) {

    /**
     * Processing status lifecycle.
     * <ul>
     *   <li>{@link #RETRYING} — discovered, currently being processed or
     *       scheduled for retry. If {@code nextAttemptAt} is in the future,
     *       the rescan loop must not re-pick it up until the backoff elapses.</li>
     *   <li>{@link #PROCESSED} — successfully forwarded to OpenELIS.
     *       Idempotent: re-drops with the same content hash are logged and
     *       skipped. File remains in the watched directory (the bridge does
     *       not touch it).</li>
     *   <li>{@link #FAILED_NEEDS_HANDLING} — exhausted retries. No further
     *       automatic retries; the file stays in place for human inspection.
     *       A structured log event is emitted on entry for a future notifier.</li>
     * </ul>
     */
    public enum Status {
        RETRYING,
        PROCESSED,
        FAILED_NEEDS_HANDLING
    }
}
