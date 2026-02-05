package org.itech.ahb.file;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Configuration properties for file-based analyzer message processing.
 * <p>
 * Configures directory watching, file patterns, and per-analyzer CSV column mappings.
 * </p>
 */
@Configuration
@ConfigurationProperties(prefix = "bridge.file")
@Data
public class FileConfig {

    /**
     * Enable/disable file watcher listener
     */
    private boolean enabled = false;

    /**
     * List of directories to watch for new files
     */
    private List<String> watchDirectories = new ArrayList<>();

    /**
     * Directory where successfully processed files are moved
     */
    private String archiveDirectory = "/mnt/analyzer-archive";

    /**
     * Directory where failed files are moved after max retry attempts
     */
    private String errorDirectory = "/mnt/analyzer-error";

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
    private List<String> filePatterns = List.of("*.csv", "*.hl7", "*.txt");

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
}
