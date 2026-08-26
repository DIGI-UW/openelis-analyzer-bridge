package org.itech.ahb.connection;

import lombok.AccessLevel;
import lombok.Data;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.itech.ahb.fhir.TabularFileLayout;
import org.itech.ahb.profile.AstmResultRecordSelection;
import org.itech.ahb.profile.ControlResultRecognition;
import org.itech.ahb.profile.TabularResultValueSelection;
import org.springframework.stereotype.Component;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * In-memory projection of active, durable analyzer connections.
 *
 * <p>The connection catalog is the sole writer. This registry gives inbound protocol handlers
 * fast source-to-analyzer lookup without creating a second configuration authority.
 */
@Component
@Slf4j
public class AnalyzerRuntimeRegistry {

    private final Map<String, AnalyzerEntry> analyzers = new ConcurrentHashMap<>();

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
        return findAnalyzerEntry(sourceId).map(AnalyzerEntry::getId);
    }

    /**
     * Finds an analyzer registry entry by source identifier.
     *
     * @param sourceId the source identifier (IP address, serial port, file path)
     * @return Optional containing the analyzer entry, or empty if no match
     */
    public Optional<AnalyzerEntry> findAnalyzerEntry(String sourceId) {
        if (sourceId == null || analyzers.isEmpty()) {
            return Optional.empty();
        }

        // Strategy 1: Direct match (IP address, serial port path)
        AnalyzerEntry entry = analyzers.get(sourceId);
        if (entry != null) {
            log.debug("Direct match for source '{}': analyzer '{}'", sourceId, entry.getId());
            return Optional.of(entry);
        }

        // Strategy 2: Pattern match (file paths with wildcards)
        for (Map.Entry<String, AnalyzerEntry> e : analyzers.entrySet()) {
            String pattern = e.getKey();
            if (pattern.contains("*") && matchesGlob(sourceId, pattern)) {
                log.debug("Pattern match for source '{}' using pattern '{}': analyzer '{}'",
                    sourceId, pattern, e.getValue().getId());
                return Optional.of(e.getValue());
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
            PathMatcher matcher = FileSystems.getDefault()
                .getPathMatcher("glob:" + globPattern);

            // Try full path match first
            if (matcher.matches(Paths.get(sourceId))) {
                return true;
            }

            // If sourceId looks like a path, also check against the file name
            Path path = Paths.get(sourceId);
            Path fileName = path.getFileName();
            return fileName != null && matcher.matches(fileName);
        } catch (Exception e) {
            log.debug("Failed to evaluate glob pattern '{}' against source '{}': {}",
                globPattern, sourceId, e.getMessage());
            return false;
        }
    }

    /**
     * Registers an analyzer by source identifier.
     * If an entry already exists for the sourceId, it is replaced.
     *
     * @param sourceId the source identifier (IP address, serial port, glob pattern)
     * @param entry    the analyzer entry to register
     */
    public synchronized void register(String sourceId, AnalyzerEntry entry) {
        analyzers.put(sourceId, entry);
        log.info("Registered analyzer '{}' (id={}) for source '{}'",
                entry.getName(), entry.getId(), sourceId);
    }

    public synchronized void unregister(String sourceId, String analyzerId) {
        AnalyzerEntry current = analyzers.get(sourceId);
        if (current != null && java.util.Objects.equals(current.getId(), analyzerId)) {
            analyzers.remove(sourceId);
            log.info("Unregistered analyzer '{}' from source '{}'", analyzerId, sourceId);
        }
    }

    /**
     * Returns all registered analyzers.
     *
     * @return unmodifiable view of the registry
     */
    public Map<String, AnalyzerEntry> getRegisteredAnalyzers() {
        return Map.copyOf(analyzers);
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

        /** Durable Bridge connection identity used for all result and order routing. */
        private String bridgeConnectionId;

        /** Exact pinned profile identity materialized from the saved connection. */
        private String profileId;

        /** Exact pinned profile revision materialized from the saved connection. */
        private int profileRevision;

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

        /**
         * Profile-owned expression used to identify an inbound sender (HL7 MSH-3/4,
         * ASTM H-record).
         */
        private String identifierPattern;

        /**
         * Compiled form of {@link #identifierPattern}, built once when the pattern is
         * set so {@code MessageNormalizer} reuses it on every inbound message instead
         * of recompiling the regex per message. {@code null} when no pattern is set or
         * when the supplied regex is invalid.
         */
        @Setter(AccessLevel.NONE)
        private transient Pattern compiledIdentifierPattern;

        /**
         * Custom setter (Lombok skips generating one): also (re)compiles
         * {@link #compiledIdentifierPattern} with {@code CASE_INSENSITIVE}. An invalid
         * regex leaves the compiled form {@code null} without throwing, so the caller
         * can detect it via {@link #getCompiledIdentifierPattern()} and choose to reject
         * or ignore. This is the single place a pattern string is turned into a Pattern.
         */
        public void setIdentifierPattern(String identifierPattern) {
            this.identifierPattern = identifierPattern;
            Pattern compiled = null;
            if (identifierPattern != null && !identifierPattern.isBlank()) {
                try {
                    compiled = Pattern.compile(identifierPattern, Pattern.CASE_INSENSITIVE);
                } catch (PatternSyntaxException e) {
                    // Leave compiled null so the owning contract validator can reject it.
                }
            }
            this.compiledIdentifierPattern = compiled;
        }

        /**
         * Column mappings for FILE protocol (spreadsheet column name → semantic field).
         * E.g., {"Sample Name": "sampleId", "Target": "testCode", "CT": "result"}
         * Materialized from the pinned Bridge profile revision.
         */
        private java.util.Map<String, String> columnMappings;

        /**
         * File format (CSV, EXCEL, TSV). Determines which parser to use.
         * Extension-based detection is the primary dispatch; this is metadata.
         */
        private String fileFormat;

        /** CSV delimiter character (default ","). Used for CSV parsing. */
        private String delimiter;

        /** Number of metadata rows to skip before header detection (default 0). */
        private int skipRows;

        /** Profile-owned instructions for locating the tabular result header. */
        private TabularFileLayout tabularFileLayout;

        /** Profile-owned precedence for selecting a reportable tabular value. */
        private TabularResultValueSelection tabularResultValueSelection;

        /**
         * Profile-derived file-wide analyzer test code for FILE exports that do
         * not carry a row-level {@code testCode}. This is materialized only when
         * the pinned profile declares exactly one primary test mapping.
         */
        private String fileTestCode;

        /**
         * Vocabulary translation for {@code FileNameSelfDeclarationScanner}:
         * maps OE test code → free-text synonyms the lab's files use
         * (e.g. {@code "VIH-1" → ["HIV-1", "GENERIC_HIV_CV"]}).
         */
        private Map<String, List<String>> scannerSynonyms = Collections.emptyMap();

        /** OE test codes this analyzer is allowed to emit (whitelist, not a default). */
        private Set<String> mappedTestCodes = Collections.emptySet();

        /** Complete control-result recognition from the pinned Bridge profile. */
        private ControlResultRecognition controlResultRecognition;

        /** Fingerprint of the exact profile-owned recognition definition. */
        private String recognitionFingerprint;

        /** Profile-owned selection of ASTM R records that carry reportable results. */
        private AstmResultRecordSelection astmResultRecordSelection;

        /**
         * Analyzer test_code → LOINC mapping materialized from the pinned profile's
         * {@code default_test_mappings}. This is the bridge's authority for translation:
         * inbound results translate code→LOINC ({@link #getLoincForCode}), and
         * outbound orders translate LOINC→code ({@link #getCodeForLoinc}). OE2
         * never sees analyzer codes — it speaks LOINC over FHIR.
         */
        private java.util.Map<String, String> codeToLoinc = Collections.emptyMap();

        /** Resolve an analyzer test code to its LOINC (inbound). Null if unmapped. */
        public String getLoincForCode(String analyzerCode) {
            if (codeToLoinc == null || analyzerCode == null) {
                return null;
            }
            return codeToLoinc.get(analyzerCode);
        }

        /** Resolve a LOINC back to this analyzer's test code (outbound). Null if unmapped. */
        public String getCodeForLoinc(String loinc) {
            if (codeToLoinc == null || loinc == null) {
                return null;
            }
            for (java.util.Map.Entry<String, String> e : codeToLoinc.entrySet()) {
                if (loinc.equals(e.getValue())) {
                    return e.getKey();
                }
            }
            return null;
        }
    }
}
