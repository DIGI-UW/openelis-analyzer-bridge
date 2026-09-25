package org.itech.ahb.connection;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import lombok.AccessLevel;
import lombok.Data;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.itech.ahb.fhir.TabularFileLayout;
import org.itech.ahb.profile.AstmResultRecordSelection;
import org.itech.ahb.profile.ControlResultRecognition;
import org.itech.ahb.profile.TabularResultValueSelection;
import org.itech.ahb.util.IpLiteral;
import org.springframework.stereotype.Component;

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
  public synchronized Optional<AnalyzerEntry> findAnalyzerEntry(String sourceId) {
    if (sourceId == null || analyzers.isEmpty()) {
      return Optional.empty();
    }

    // Strategy 1: Direct match (IP address, serial port path)
    AnalyzerEntry entry = analyzers.get(sourceId);
    if (entry != null) {
      log.debug("Direct match for source '{}': analyzer '{}'", sourceId, entry.getId());
      return Optional.of(entry);
    }

    // Host aliases are usable only when exactly one durable connection claims them.
    List<AnalyzerEntry> aliases = analyzers
      .values()
      .stream()
      .filter(candidate -> sourceId.equals(candidate.getInboundSourceId()))
      .limit(2)
      .toList();
    if (!aliases.isEmpty()) {
      return aliases.size() == 1 ? Optional.of(aliases.get(0)) : Optional.empty();
    }

    // Pattern match (file paths with wildcards)
    for (Map.Entry<String, AnalyzerEntry> e : analyzers.entrySet()) {
      String pattern = e.getKey();
      if (pattern.contains("*") && matchesGlob(sourceId, pattern)) {
        log.debug(
          "Pattern match for source '{}' using pattern '{}': analyzer '{}'",
          sourceId,
          pattern,
          e.getValue().getId()
        );
        return Optional.of(e.getValue());
      }
    }

    return Optional.empty();
  }

  /** How a message received on a shared listener resolved to a saved connection, or why it did not. */
  public sealed interface Resolution {
    /** The one connection the message belongs to, and which rung of the ladder decided it. */
    record Resolved(AnalyzerEntry entry, String basis) implements Resolution {}

    /** No active connection can own the message. */
    record Unregistered(String detail) implements Resolution {}

    /** More than one connection could own the message and nothing in it tells them apart. */
    record Ambiguous(List<String> connectionIds, String detail) implements Resolution {}
  }

  /**
   * Resolves the saved connection for one inbound message.
   *
   * <p>A message that arrived on a shared network listener ({@code listenerPort} set) is resolved
   * among the active connections that declare that listener, in this order:
   * <ol>
   *   <li>the peer address against each connection's {@code host};</li>
   *   <li>the message's sender name (ASTM H.5 or HL7 MSH-3, component 1) against each connection's
   *       {@code senderId};</li>
   *   <li>the pinned profile's {@code identifier_pattern}, which can only rule a connection out;</li>
   *   <li>uniqueness: one candidate left.</li>
   * </ol>
   * A connection whose literal {@code host} is a different address, or whose {@code senderId} names a
   * different instrument, is never a candidate. Nothing is guessed between the candidates that
   * remain: those cases come back unregistered or ambiguous so the message is dead-lettered with its
   * payload.
   *
   * <p>Without a listener port (serial, file, HTTP, and messages stored before 3.2.0 under a
   * {@code connection:} key) the source identifier is looked up directly, as before.
   */
  public synchronized Resolution resolve(Integer listenerPort, String sourceId, String senderHint) {
    if (listenerPort == null) {
      return findAnalyzerEntry(sourceId)
        .<Resolution>map(entry -> new Resolution.Resolved(entry, "source"))
        .orElseGet(() -> new Resolution.Unregistered("No saved analyzer connection for source " + sourceId));
    }

    List<AnalyzerEntry> onListener = analyzers
      .values()
      .stream()
      .filter(entry -> listenerPort.equals(entry.getListenerPort()))
      .sorted(Comparator.comparing(AnalyzerEntry::getBridgeConnectionId, Comparator.nullsLast(String::compareTo)))
      .toList();
    if (onListener.isEmpty()) {
      return new Resolution.Unregistered("No active analyzer connection listens on port " + listenerPort);
    }

    String peer = IpLiteral.canonicalize(sourceId);
    List<AnalyzerEntry> byAddress = onListener
      .stream()
      .filter(entry -> peer != null && peer.equals(entry.getInboundAddress()))
      .toList();
    // A connection whose host is a different literal address is another machine: never a candidate.
    List<AnalyzerEntry> candidates = byAddress.isEmpty()
      ? onListener.stream().filter(entry -> entry.getInboundAddress() == null).toList()
      : byAddress;

    String sender = senderName(senderHint);
    if (sender == null) {
      candidates = candidates.stream().filter(entry -> entry.getSenderId() == null).toList();
    } else {
      List<AnalyzerEntry> bySender = candidates
        .stream()
        .filter(entry -> entry.getSenderId() != null && entry.getSenderId().equalsIgnoreCase(sender))
        .toList();
      if (bySender.size() == 1) {
        return new Resolution.Resolved(bySender.get(0), "sender");
      }
      if (bySender.isEmpty()) {
        // A connection that names another instrument is that instrument, not this one.
        candidates = candidates.stream().filter(entry -> entry.getSenderId() == null).toList();
      } else {
        candidates = bySender;
      }
    }
    if (senderHint != null && candidates.size() > 1) {
      // A type-level pattern (GENEXPERT|CEPHEID) rules out another kind of analyzer; it never picks one.
      candidates = candidates
        .stream()
        .filter(
          entry ->
            entry.getCompiledIdentifierPattern() == null ||
            entry.getCompiledIdentifierPattern().matcher(senderHint).find()
        )
        .toList();
    }

    if (candidates.size() == 1) {
      return new Resolution.Resolved(candidates.get(0), byAddress.size() == 1 ? "address" : "uniqueness");
    }
    if (candidates.isEmpty()) {
      return new Resolution.Unregistered(
        "No active analyzer connection on port " +
        listenerPort +
        " is configured for address " +
        sourceId +
        (sender == null ? "" : " and sender " + sender)
      );
    }
    List<String> ids = new ArrayList<>();
    candidates.forEach(entry -> ids.add(entry.getBridgeConnectionId()));
    return new Resolution.Ambiguous(
      ids,
      "Connections " +
      String.join(", ", ids) +
      " on port " +
      listenerPort +
      " cannot be told apart for a message from " +
      sourceId +
      "; set a distinct host on each, or set senderId to each instrument's system name"
    );
  }

  /**
   * The instrument's own name from a sender field: component 1 of ASTM H.5 or HL7 MSH-3, which a
   * GeneXpert fills with the System Name from its configuration.
   */
  static String senderName(String senderHint) {
    if (senderHint == null) {
      return null;
    }
    String first = senderHint.split("\\^", -1)[0].trim();
    return first.isEmpty() ? null : first;
  }

  /**
   * Another active connection on the same listener that no message could be told apart from, or
   * null. Two connections are indistinguishable when they share an address (or both have none) and
   * their sender names do not separate them.
   */
  public synchronized AnalyzerEntry indistinguishableFrom(AnalyzerEntry candidate) {
    if (candidate.getListenerPort() == null) {
      return null;
    }
    return analyzers
      .values()
      .stream()
      .filter(other -> candidate.getListenerPort().equals(other.getListenerPort()))
      .filter(other -> !java.util.Objects.equals(other.getBridgeConnectionId(), candidate.getBridgeConnectionId()))
      .filter(other -> java.util.Objects.equals(other.getInboundAddress(), candidate.getInboundAddress()))
      .filter(other -> sameSender(other.getSenderId(), candidate.getSenderId()))
      .findFirst()
      .orElse(null);
  }

  /** Both unnamed, or both the same name: a message's sender cannot tell them apart. */
  private static boolean sameSender(String left, String right) {
    return left == null ? right == null : left.equalsIgnoreCase(right);
  }

  /** Finds the active runtime projection for one durable Bridge connection. */
  public Optional<AnalyzerEntry> findAnalyzerEntryByConnectionId(String connectionId) {
    if (connectionId == null || connectionId.isBlank()) {
      return Optional.empty();
    }
    return analyzers.values().stream().filter(entry -> connectionId.equals(entry.getBridgeConnectionId())).findFirst();
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
      PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + globPattern);

      // Try full path match first
      if (matcher.matches(Paths.get(sourceId))) {
        return true;
      }

      // If sourceId looks like a path, also check against the file name
      Path path = Paths.get(sourceId);
      Path fileName = path.getFileName();
      return fileName != null && matcher.matches(fileName);
    } catch (Exception e) {
      log.debug("Failed to evaluate glob pattern '{}' against source '{}': {}", globPattern, sourceId, e.getMessage());
      return false;
    }
  }

  /**
   * Registers a projection under its connection-specific source binding.
   * Replacing this binding must not replace another connection on the same host.
   *
   * @param sourceId the source identifier (IP address, serial port, glob pattern)
   * @param entry    the analyzer entry to register
   */
  public synchronized void register(String sourceId, AnalyzerEntry entry) {
    analyzers.put(sourceId, entry);
    log.info("Registered analyzer '{}' (id={}) for source '{}'", entry.getName(), entry.getId(), sourceId);
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

    /** Immutable profile fingerprint for receipt-time recovery. */
    private String profileFingerprint;

    /**
     * Human-readable analyzer name (e.g., "Mindray BC-5380")
     */
    private String name;

    /** Optional host alias; never used as the durable connection's registry key. */
    private String inboundSourceId;

    /**
     * Shared network listener this connection receives on (the saved {@code port} of a SERVER
     * connection), or null when the transport has no shared listener.
     */
    private Integer listenerPort;

    /** Canonical literal IP from the saved {@code host}; null when unset or given as a hostname. */
    private String inboundAddress;

    /**
     * The instrument's own name as it sends it (ASTM H.5 / HL7 MSH-3 component 1; a GeneXpert's
     * configured System Name). Separates connections that share a listener and an address.
     */
    private String senderId;

    /** Saved transport, used to restrict incoming traffic to the configured delivery path. */
    private String inboundTransport;

    /** True only when the pinned profile explicitly permits LIS-initiated orders. */
    private boolean outboundOrdersSupported;

    /**
     * Expected protocol (ASTM, HL7, CSV) for validation
     */
    private String expectedProtocol;

    /** Bridge-owned network destination for LIS-initiated orders, when configured. */
    private String outboundHost;

    /** Bridge-owned network port for LIS-initiated orders, when configured. */
    private int outboundPort;

    /**
     * Optional file pattern for additional validation (regex)
     */
    private String filePattern;

    /** Actual directory materialized from saved connection values, never a registry key. */
    private String fileDirectory;

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

    /** Specimen group layout materialized only from the pinned HL7 profile. */
    private org.itech.ahb.profile.Hl7SpecimenPosition hl7SpecimenPosition =
      org.itech.ahb.profile.Hl7SpecimenPosition.PRECEDING;

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
