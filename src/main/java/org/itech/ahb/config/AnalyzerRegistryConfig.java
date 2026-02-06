package org.itech.ahb.config;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.PatternSyntaxException;

/**
 * Configuration properties for analyzer identification registry.
 * <p>
 * Maps source identifiers (IP addresses, serial ports, file paths) to analyzer IDs
 * for the {@link org.itech.ahb.normalizer.AnalyzerIdentifier} service.
 * </p>
 * <p>
 * Configuration format in {@code configuration.yml}:
 * <pre>
 * bridge:
 *   analyzers:
 *     "192.168.1.10":
 *       id: MINDRAY-BC5380-001
 *       name: "Mindray BC-5380"
 *       expectedProtocol: ASTM
 *     "/dev/ttyUSB0":
 *       id: HORIBA-PENTRA60-001
 *       name: "Horiba Pentra 60"
 *       expectedProtocol: ASTM
 *     "quantstudio-*":
 *       id: QUANTSTUDIO-001
 *       name: "QuantStudio 7 Flex"
 *       expectedProtocol: CSV
 *       filePattern: ".* /quantstudio-.*\\.csv"
 * </pre>
 * </p>
 * <p>
 * Keys can be:
 * <ul>
 *   <li><strong>IP addresses:</strong> Exact match (e.g., "192.168.1.10")</li>
 *   <li><strong>Serial port paths:</strong> Exact match (e.g., "/dev/ttyUSB0")</li>
 *   <li><strong>Glob patterns:</strong> Wildcard match for file paths (e.g., "quantstudio-*")</li>
 * </ul>
 * </p>
 *
 * @see org.itech.ahb.normalizer.AnalyzerIdentifier
 */
@Configuration
@ConfigurationProperties(prefix = "bridge")
@Data
@Slf4j
public class AnalyzerRegistryConfig {

    /**
     * Map of source identifiers to analyzer entries.
     * <p>
     * Keys are source identifiers (IP addresses, serial ports, glob patterns).
     * Values are {@link AnalyzerEntry} objects with analyzer metadata.
     * </p>
     */
    private Map<String, AnalyzerEntry> analyzers = new LinkedHashMap<>();

    /**
     * Finds an analyzer ID by source identifier.
     * <p>
     * Lookup strategy:
     * <ol>
     *   <li><strong>Direct match:</strong> Exact key match (IP address, serial port)</li>
     *   <li><strong>Pattern match:</strong> Glob pattern match for file paths (keys with "*")</li>
     * </ol>
     * </p>
     *
     * @param sourceId the source identifier (IP address, serial port, file path)
     * @return Optional containing the analyzer ID, or empty if no match
     */
    public Optional<String> findAnalyzerId(String sourceId) {
        if (sourceId == null || analyzers.isEmpty()) {
            return Optional.empty();
        }

        // Strategy 1: Direct match (IP address, serial port path)
        AnalyzerEntry entry = analyzers.get(sourceId);
        if (entry != null) {
            log.debug("Direct match for source '{}': analyzer '{}'", sourceId, entry.getId());
            return Optional.of(entry.getId());
        }

        // Strategy 2: Pattern match (file paths with wildcards)
        for (Map.Entry<String, AnalyzerEntry> e : analyzers.entrySet()) {
            String pattern = e.getKey();
            if (pattern.contains("*") && matchesGlob(sourceId, pattern)) {
                log.debug("Pattern match for source '{}' using pattern '{}': analyzer '{}'",
                    sourceId, pattern, e.getValue().getId());
                return Optional.of(e.getValue().getId());
            }
        }

        return Optional.empty();
    }

    /**
     * Checks if a source ID matches a glob pattern.
     * <p>
     * Converts glob pattern to regex:
     * <ul>
     *   <li>{@code *} becomes {@code .*} (match any characters)</li>
     *   <li>{@code ?} becomes {@code .} (match single character)</li>
     *   <li>Special regex chars are escaped</li>
     * </ul>
     * </p>
     *
     * @param sourceId the source identifier to test
     * @param globPattern the glob pattern (may contain * or ?)
     * @return true if sourceId matches the pattern
     */
    private boolean matchesGlob(String sourceId, String globPattern) {
        try {
            // Convert glob to regex
            String regex = globPattern
                .replace(".", "\\.")  // Escape dots
                .replace("*", ".*")   // * matches any characters
                .replace("?", ".");   // ? matches single character
            if (sourceId.matches(regex)) {
                return true;
            }

            // If sourceId looks like a path, also check against the file name
            Path path = Paths.get(sourceId);
            Path fileName = path.getFileName();
            return fileName != null && fileName.toString().matches(regex);
        } catch (PatternSyntaxException e) {
            log.warn("Invalid glob pattern '{}': {}", globPattern, e.getMessage());
            return false;
        } catch (Exception e) {
            log.debug("Failed to evaluate glob pattern '{}' against source '{}': {}",
                globPattern, sourceId, e.getMessage());
            return false;
        }
    }

    /**
     * Analyzer entry with metadata.
     * <p>
     * Contains analyzer identification and configuration information.
     * </p>
     */
    @Data
    public static class AnalyzerEntry {
        /**
         * Unique analyzer identifier (e.g., "MINDRAY-BC5380-001")
         */
        private String id;

        /**
         * Human-readable analyzer name (e.g., "Mindray BC-5380")
         */
        private String name;

        /**
         * Expected protocol (ASTM, HL7, CSV) for validation
         */
        private String expectedProtocol;

        /**
         * Optional file pattern for additional validation (regex)
         */
        private String filePattern;
    }
}
