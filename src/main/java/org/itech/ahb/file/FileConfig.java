package org.itech.ahb.file;

import lombok.Data;
import org.itech.ahb.model.Protocol;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Configuration properties for file-based analyzer message processing.
 * <p>
 * Configures directory watching, file patterns, and per-analyzer CSV column mappings.
 * </p>
 * <p>
 * NOTE: Configuration validation should be added when Jakarta Validation dependency is available.
 * Ensure: watchDirectories not empty, positive timeouts, maxRetryAttempts >= 1
 * </p>
 */
@Configuration
@ConfigurationProperties(prefix = "bridge.file")
@Data
public class FileConfig {

    /**
     * Enable/disable file watcher listener.
     * Default: true — directories are registered at runtime via the REST API,
     * so enabling the watcher has no cost when no directories are registered.
     * Set to false only if the FILE transport should be completely disabled.
     */
    private boolean enabled = true;

    /**
     * List of directories to watch for new files
     */
    private List<String> watchDirectories = new ArrayList<>();

    /**
     * Path to the SQLite database that holds per-file processing state
     * (see {@link FileStateStore}). This database is the ONLY place the
     * bridge persists information about which files have been processed
     * vs. failed — the watched directories themselves are strictly
     * read-only from the bridge's point of view, so no archive, error,
     * or .failed sidecar files are ever created.
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

    /**
     * File patterns to watch (glob patterns)
     * Default: *.csv, *.hl7, *.txt
     */
    private List<String> filePatterns = new ArrayList<>(List.of("*.csv", "*.hl7", "*.txt"));

    /**
     * Per-analyzer CSV column mappings
     * Key: analyzer ID (e.g., "QUANTSTUDIO-001")
     * Value: Map of field name to column index
     * <p>
     * Example:
     * csvMappings:
     *   QUANTSTUDIO-001:
     *     sampleId: 0
     *     testCode: 1
     *     result: 2
     *     units: 3
     * </p>
     */
    private Map<String, Map<String, Integer>> csvMappings = new HashMap<>();

    /**
     * Analyzer identification configuration.
     * Map of pattern string to AnalyzerConfig.
     * <p>
     * Pattern Matching Strategy:
     * <ul>
     *   <li>If {@code filePattern} is specified in AnalyzerConfig, it overrides the map key pattern</li>
     *   <li>Patterns containing wildcards (* or ?) use glob matching against the filename</li>
     *   <li>Patterns without wildcards use substring matching against the full path and filename</li>
     * </ul>
     * Examples:
     * <ul>
     *   <li>Key: "quantstudio-*", filePattern: null - glob match on "quantstudio-*"</li>
     *   <li>Key: "QUANTSTUDIO-001", filePattern: "quantstudio-*.csv" - glob match on "quantstudio-*.csv"</li>
     *   <li>Key: "analyzer1", filePattern: null - substring match on "analyzer1"</li>
     * </ul>
     * </p>
     */
    private Map<String, AnalyzerConfig> analyzers = new HashMap<>();

    /**
     * Get CSV column mapping for a specific analyzer
     *
     * @param analyzerId the analyzer identifier
     * @return column mapping (field name to column index), or null if not configured
     */
    public Map<String, Integer> getCsvMappingForAnalyzer(String analyzerId) {
        return csvMappings.get(analyzerId);
    }

    /**
     * Check if a specific analyzer has custom CSV mappings configured
     *
     * @param analyzerId the analyzer identifier
     * @return true if custom mappings exist
     */
    public boolean hasCustomCsvMapping(String analyzerId) {
        return csvMappings.containsKey(analyzerId) && !csvMappings.get(analyzerId).isEmpty();
    }

    /**
     * Get analyzer configuration by pattern
     *
     * @param pattern the pattern string
     * @return AnalyzerConfig or null if not found
     */
    public AnalyzerConfig getAnalyzerByPattern(String pattern) {
        return analyzers.get(pattern);
    }

    /**
     * Analyzer configuration for pattern-based identification
     */
    @Data
    public static class AnalyzerConfig {
        /**
         * Unique analyzer identifier (e.g., "QUANTSTUDIO-001")
         */
        private String id;

        /**
         * Human-readable analyzer name
         */
        private String name;

        /**
         * Expected protocol for this analyzer
         */
        private Protocol expectedProtocol;

        /**
         * File pattern regex for matching (optional, overrides pattern key)
         */
        private String filePattern;
    }
}
