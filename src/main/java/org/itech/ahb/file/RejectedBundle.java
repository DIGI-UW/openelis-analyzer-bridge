package org.itech.ahb.file;

import java.time.Instant;

/**
 * Immutable record of a FHIR bundle (or protocol payload) that the bridge
 * attempted to forward but OE rejected with a non-retryable 4xx or that
 * exhausted retries on 5xx/IO.
 * <p>
 * These entries replace the previous "silent log-and-drop" behavior: without
 * a durable record, a bridge → OE rejection is invisible once log rotation
 * discards the event. An operator-visible admin endpoint reads from this
 * store so the OE Import Issues dashboard (A4) can surface bundles that
 * never became staging rows.
 * </p>
 * <p>
 * Keyed on {@link #id} (UUID assigned at record time). {@code payloadHash}
 * allows deduping repeat failures of the same bundle across retries at
 * higher layers.
 * </p>
 */
public record RejectedBundle(
        String id,
        String sourceId,
        String protocol,
        int httpStatus,
        String lastError,
        String payloadHash,
        String payloadSnippet,
        Instant rejectedAt,
        boolean dismissed
) {
}
