package org.itech.ahb.mllp;

import ca.uhn.hl7v2.HL7Exception;
import ca.uhn.hl7v2.model.Message;
import ca.uhn.hl7v2.protocol.ReceivingApplication;
import ca.uhn.hl7v2.protocol.ReceivingApplicationException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;

/**
 * Decorator for ReceivingApplication that adds per-IP rate limiting.
 * <p>
 * Fixes PR review comments:
 * <ul>
 *   <li>#5: Race condition - uses atomic {@link ConcurrentHashMap#compute} for check-and-update</li>
 *   <li>#7: Memory leak - scheduled cleanup removes entries older than 1 hour</li>
 * </ul>
 * </p>
 * <p>
 * Default rate: 10 messages per second per source IP (100ms minimum interval).
 * </p>
 */
@Slf4j
public class RateLimitingReceivingApplication implements ReceivingApplication<Message> {

    /** Minimum milliseconds between messages from the same IP (100ms = 10 msg/sec per IP) */
    private static final long MIN_INTERVAL_MS = 100;

    /** How often to run cleanup of stale entries (5 minutes) */
    private static final long CLEANUP_INTERVAL_MS = 300_000;

    /** Maximum age of entries before cleanup (1 hour) */
    private static final long MAX_ENTRY_AGE_MS = 3_600_000;

    private final ReceivingApplication<Message> delegate;
    private final ConcurrentHashMap<String, Long> lastMessageTime;
    private final ScheduledExecutorService cleanupExecutor;

    /**
     * Wraps a ReceivingApplication with per-IP rate limiting.
     *
     * @param delegate the actual receiving application to delegate to
     */
    public RateLimitingReceivingApplication(ReceivingApplication<Message> delegate) {
        this.delegate = delegate;
        this.lastMessageTime = new ConcurrentHashMap<>();

        // Schedule periodic cleanup to prevent memory leak from IP accumulation
        this.cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r);
            t.setName("mllp-rate-limit-cleanup");
            t.setDaemon(true);
            return t;
        });

        cleanupExecutor.scheduleAtFixedRate(
            this::cleanupOldEntries,
            0,
            CLEANUP_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        );
    }

    /**
     * Processes a message with rate limiting applied.
     * <p>
     * Uses atomic {@link ConcurrentHashMap#compute} to prevent race conditions
     * where multiple threads could bypass the rate limit simultaneously.
     * </p>
     *
     * @param message the HL7 message
     * @param metadata connection metadata from HAPI
     * @return the response from the delegate
     * @throws ReceivingApplicationException if rate limited or delegate fails
     */
    @Override
    public Message processMessage(Message message, Map<String, Object> metadata)
            throws ReceivingApplicationException, HL7Exception {

        String sourceIp = extractSourceIp(metadata);
        long now = System.currentTimeMillis();

        // Atomic check-and-update to prevent race condition (fixes review comment #5)
        final boolean[] rateLimited = new boolean[1];
        final long[] elapsedSinceLast = new long[1];

        lastMessageTime.compute(sourceIp, (ip, lastTime) -> {
            if (lastTime != null) {
                long elapsed = now - lastTime;
                if (elapsed < MIN_INTERVAL_MS) {
                    rateLimited[0] = true;
                    elapsedSinceLast[0] = elapsed;
                    return lastTime; // Don't update timestamp on rejected message
                }
            }
            return now; // Accept: update timestamp
        });

        if (rateLimited[0]) {
            log.warn("Rate limit exceeded for {}: {}ms since last message (min: {}ms)",
                sourceIp, elapsedSinceLast[0], MIN_INTERVAL_MS);
            throw new ReceivingApplicationException(
                "Rate limit exceeded for " + sourceIp + ": " +
                elapsedSinceLast[0] + "ms since last message");
        }

        return delegate.processMessage(message, metadata);
    }

    /**
     * Delegates canProcess check to the wrapped application.
     *
     * @param message the HL7 message to check
     * @return true if the delegate can process the message
     */
    @Override
    public boolean canProcess(Message message) {
        return delegate.canProcess(message);
    }

    /**
     * Removes entries older than MAX_ENTRY_AGE_MS to prevent memory leak.
     * <p>
     * Fixes PR review comment #7: lastConnectionTime map can grow unbounded.
     * </p>
     */
    void cleanupOldEntries() {
        long cutoff = System.currentTimeMillis() - MAX_ENTRY_AGE_MS;
        int removed = 0;

        var iterator = lastMessageTime.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue() < cutoff) {
                iterator.remove();
                removed++;
            }
        }

        if (removed > 0) {
            log.debug("Cleaned up {} stale rate limit entries", removed);
        }
    }

    /**
     * Shuts down the cleanup executor. Should be called during server shutdown.
     */
    public void shutdown() {
        cleanupExecutor.shutdown();
        try {
            if (!cleanupExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                cleanupExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            cleanupExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Extracts source IP from HAPI metadata.
     *
     * @param metadata HAPI connection metadata
     * @return source IP address or "unknown"
     */
    private String extractSourceIp(Map<String, Object> metadata) {
        Object sendingIp = metadata.get(HapiReceivingApplication.META_SENDING_IP);
        return sendingIp != null ? sendingIp.toString() : "unknown";
    }

    /**
     * Returns the number of tracked IPs (for testing/monitoring).
     *
     * @return number of IPs currently tracked
     */
    int getTrackedIpCount() {
        return lastMessageTime.size();
    }
}
