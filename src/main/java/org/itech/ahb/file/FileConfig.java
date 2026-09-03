package org.itech.ahb.file;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Paths;

/**
 * Configuration properties for file-based analyzer message processing.
 * <p>
 * Configures process-wide FILE transport polling and durable state behavior.
 * </p>
 * <p>
 * NOTE: Configuration validation should be added when Jakarta Validation dependency is available.
 * Ensure: positive timeouts, maxRetryAttempts >= 1
 * </p>
 */
@Configuration
@ConfigurationProperties(prefix = "bridge.file")
@Data
public class FileConfig {

    /**
     * Enable/disable file watcher listener.
     * Default: true — directories are registered when durable FILE connections
     * are activated, so enabling the watcher has no cost when none are active.
     * Set to false only if the FILE transport should be completely disabled.
     */
    private boolean enabled = true;

    /**
     * Path to the SQLite database that holds per-file processing state
     * (see {@link FileStateStore}). This database is the ONLY place the
     * bridge persists information about file-processing outcomes. Watched
     * directories remain read-only from the bridge's point of view.
     * <p>
     * Default uses the JVM temp directory so tests and local runs work
     * without a pre-configured volume. Production deployments should
     * override this to a persistent path (e.g.
     * {@code /data/openelis-analyzer-bridge/state.db}).
     * </p>
     * <p>
     * <b>Invariant:</b> one bridge JVM per state.db. Blue/green deploys
     * must not run two bridges against the same file.
     * </p>
     */
    private String stateStorePath = Paths.get(
            System.getProperty("java.io.tmpdir"), "openelis-analyzer-bridge", "state.db").toString();

    /**
     * Polling interval in milliseconds for checking new files
     */
    private long pollIntervalMs = 5000;

    /**
     * File stability timeout in milliseconds (wait after last modification)
     * Ensures file is fully written before processing
     */
    private long fileStabilityTimeoutMs = 3000;

    /**
     * Maximum number of retry attempts for failed file processing
     */
    private int maxRetryAttempts = 3;

    /**
     * Initial retry delay in milliseconds (exponential backoff)
     */
    private long retryDelayMs = 1000;

}
