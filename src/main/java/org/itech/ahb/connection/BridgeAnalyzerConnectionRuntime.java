package org.itech.ahb.connection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.itech.ahb.connection.AnalyzerRuntimeRegistry.AnalyzerEntry;
import org.itech.ahb.fhir.TabularFileLayout;
import org.itech.ahb.file.FileWatcher;
import org.itech.ahb.profile.AstmResultRecordSelection;
import org.itech.ahb.profile.ControlResultRecognition;
import org.itech.ahb.profile.TabularResultValueSelection;
import org.itech.ahb.util.IpLiteral;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Materializes durable connections into the established Bridge runtime. */
public final class BridgeAnalyzerConnectionRuntime implements AnalyzerConnectionRuntime {

  private static final Logger log = LoggerFactory.getLogger(BridgeAnalyzerConnectionRuntime.class);

  private final AnalyzerRuntimeRegistry registry;
  private final FileWatcher fileWatcher;
  private final AstmConnectionListeners astmListeners;
  private final SerialConnectionListeners serialListeners;
  private final Hl7ConnectionListeners hl7Listeners;
  private final Map<String, ActiveMaterialization> activeConnections = new ConcurrentHashMap<>();

  public BridgeAnalyzerConnectionRuntime(
    AnalyzerRuntimeRegistry registry,
    FileWatcher fileWatcher,
    AstmConnectionListeners astmListeners,
    SerialConnectionListeners serialListeners
  ) {
    this(registry, fileWatcher, astmListeners, serialListeners, null);
  }

  public BridgeAnalyzerConnectionRuntime(
    AnalyzerRuntimeRegistry registry,
    FileWatcher fileWatcher,
    AstmConnectionListeners astmListeners,
    SerialConnectionListeners serialListeners,
    Hl7ConnectionListeners hl7Listeners
  ) {
    this.registry = registry;
    this.fileWatcher = fileWatcher;
    this.astmListeners = astmListeners;
    this.serialListeners = serialListeners;
    this.hl7Listeners = hl7Listeners;
  }

  @Override
  public synchronized void activate(ObjectNode connection, ObjectNode profile) {
    activate(connection, profile, true);
  }

  private void activate(ObjectNode connection, ObjectNode profile, boolean refuseIndistinguishable) {
    String connectionId = requiredText(connection, "connectionId", "Connection ID");
    ActiveMaterialization replacement = materialization(connection, profile);
    AnalyzerEntry twin = registry.indistinguishableFrom(replacement.entry());
    if (twin != null) {
      String detail =
        "Connection " + connectionId + " on port " + replacement.entry().getListenerPort() +
        " cannot be told apart from active connection " + twin.getBridgeConnectionId() +
        " (" + twin.getName() + "): give each a distinct host, or set senderId to each instrument's system name";
      if (refuseIndistinguishable) {
        throw new AnalyzerConnectionException(AnalyzerConnectionException.Kind.CONFLICT, detail);
      }
      // Restoring what was already active must not stop the bridge from starting; messages the
      // pair cannot be told apart for are dead-lettered as AMBIGUOUS_SOURCE instead.
      log.warn("{}. Restoring it anyway; its unattributable messages will be held as AMBIGUOUS_SOURCE", detail);
    }
    ActiveMaterialization previous = activeConnections.get(connectionId);
    if (previous != null) {
      deactivateMaterialization(previous);
      activeConnections.remove(connectionId);
    }

    try {
      activateMaterialization(replacement);
      activeConnections.put(connectionId, replacement);
    } catch (RuntimeException exception) {
      if (previous != null) {
        try {
          activateMaterialization(previous);
          activeConnections.put(connectionId, previous);
        } catch (RuntimeException rollbackFailure) {
          exception.addSuppressed(rollbackFailure);
        }
      }
      throw exception;
    }
  }

  @Override
  public synchronized void deactivate(ObjectNode connection, ObjectNode profile) {
    String connectionId = requiredText(connection, "connectionId", "Connection ID");
    ActiveMaterialization active = activeConnections.get(connectionId);
    if (active != null) {
      deactivateMaterialization(active);
      activeConnections.remove(connectionId);
      return;
    }

    String analyzerId = requiredText(connection, "clientAnalyzerId", "OpenELIS analyzer ID");
    ObjectNode values = requiredObject(connection, "values");
    String protocol = requiredText(profile.path("protocol"), "name", "Profile protocol");
    deactivateTransport(protocol, connectionId, analyzerId, values);
    registry.unregister(registryKey(protocol, connectionId, values), analyzerId);
  }

  @Override
  public synchronized void restore(ObjectNode connection, ObjectNode profile) {
    activate(connection, profile, false);
  }

  private ActiveMaterialization materialization(ObjectNode connection, ObjectNode profile) {
    ObjectNode savedConnection = connection.deepCopy();
    ObjectNode pinnedProfile = profile.deepCopy();
    String protocol = requiredText(pinnedProfile.path("protocol"), "name", "Profile protocol");
    String connectionId = requiredText(savedConnection, "connectionId", "Connection ID");
    String analyzerId = requiredText(savedConnection, "clientAnalyzerId", "OpenELIS analyzer ID");
    ObjectNode values = requiredObject(savedConnection, "values");
    return new ActiveMaterialization(
      savedConnection,
      pinnedProfile,
      registryKey(protocol, connectionId, values),
      materialize(analyzerId, savedConnection, pinnedProfile, values)
    );
  }

  private void activateMaterialization(ActiveMaterialization materialization) {
    ObjectNode connection = materialization.connection();
    ObjectNode profile = materialization.profile();
    String connectionId = requiredText(connection, "connectionId", "Connection ID");
    String analyzerId = requiredText(connection, "clientAnalyzerId", "OpenELIS analyzer ID");
    String protocol = requiredText(profile.path("protocol"), "name", "Profile protocol");
    ObjectNode values = requiredObject(connection, "values");
    registry.register(materialization.registryKey(), materialization.entry());
    try {
      activateTransport(protocol, connectionId, materialization.registryKey(), analyzerId, profile, values);
    } catch (RuntimeException exception) {
      registry.unregister(materialization.registryKey(), analyzerId);
      throw exception;
    }
  }

  private void deactivateMaterialization(ActiveMaterialization materialization) {
    ObjectNode connection = materialization.connection();
    ObjectNode profile = materialization.profile();
    String connectionId = requiredText(connection, "connectionId", "Connection ID");
    String analyzerId = requiredText(connection, "clientAnalyzerId", "OpenELIS analyzer ID");
    deactivateTransport(
      requiredText(profile.path("protocol"), "name", "Profile protocol"),
      connectionId,
      analyzerId,
      requiredObject(connection, "values")
    );
    registry.unregister(materialization.registryKey(), analyzerId);
  }

  private record ActiveMaterialization(
    ObjectNode connection,
    ObjectNode profile,
    String registryKey,
    AnalyzerEntry entry
  ) {}

  private void activateTransport(
    String protocol,
    String connectionId,
    String sourceBindingId,
    String analyzerId,
    ObjectNode profile,
    ObjectNode values
  ) {
    if ("HTTP".equals(nullableText(values, "transport"))) {
      if (
        !"FILE".equals(protocol) ||
        !("CSV".equals(nullableText(values, "fileFormat")) || "TSV".equals(nullableText(values, "fileFormat")))
      ) {
        throw new AnalyzerConnectionException("HTTP input requires a tabular CSV or TSV profile");
      }
      // The shared HTTP controller receives traffic; this connection owns its sender binding.
      return;
    }
    if ("FILE".equals(protocol)) {
      if (fileWatcher == null) {
        throw new AnalyzerConnectionException("FILE runtime is disabled in this Bridge deployment");
      }
      Path directory = Path.of(requiredText(values, "directory", "FILE directory")).normalize();
      String filePattern = requiredText(values, "filePattern", "FILE pattern");
      try {
        fileWatcher.addWatchDirectory(directory, filePattern, analyzerId);
      } catch (IOException exception) {
        throw new AnalyzerConnectionException("Cannot activate FILE watch for " + analyzerId, exception);
      }
      return;
    }

    if ("ASTM".equals(protocol) && "TCP/IP".equals(nullableText(values, "transport"))) {
      if ("SERVER".equals(nullableText(values, "connectionRole"))) {
        astmListeners.start(
          connectionId,
          analyzerId,
          requiredPort(values, "port"),
          requiredText(profile.path("protocol"), "lowerLayerVersion", "ASTM lower-layer version")
        );
      }
      return;
    }

    if ("HL7".equals(protocol) && "TCP/IP".equals(nullableText(values, "transport"))) {
      if (!"SERVER".equals(nullableText(values, "connectionRole"))) {
        throw new AnalyzerConnectionException("Inbound HL7 TCP requires a saved SERVER connection");
      }
      if (hl7Listeners == null) throw new AnalyzerConnectionException("HL7 listener runtime is unavailable");
      hl7Listeners.start(connectionId, sourceBindingId, requiredPort(values, "port"));
      return;
    }

    if ("RS-232".equals(nullableText(values, "transport"))) {
      if (serialListeners == null) {
        throw new AnalyzerConnectionException("Serial runtime is unavailable in this Bridge deployment");
      }
      serialListeners.start(
        connectionId,
        sourceBindingId,
        analyzerId,
        requiredText(values, "serialPort", "Serial port"),
        SerialConnectionSettings.fromProfile(profile)
      );
      return;
    }

    throw new AnalyzerConnectionException(
      "Runtime activation is not implemented for the saved " + protocol + " transport"
    );
  }

  private void deactivateTransport(String protocol, String connectionId, String analyzerId, ObjectNode values) {
    if ("HTTP".equals(nullableText(values, "transport"))) {
      return;
    }
    if ("FILE".equals(protocol)) {
      if (fileWatcher != null) {
        Path directory = Path.of(requiredText(values, "directory", "FILE directory")).normalize();
        fileWatcher.removeWatchRegistration(directory, analyzerId);
      }
    } else if ("RS-232".equals(nullableText(values, "transport"))) {
      if (serialListeners != null) {
        serialListeners.stop(connectionId);
      }
    } else if (
      "ASTM".equals(protocol) &&
      "TCP/IP".equals(nullableText(values, "transport")) &&
      "SERVER".equals(nullableText(values, "connectionRole"))
    ) {
      astmListeners.stop(connectionId);
    } else if ("HL7".equals(protocol) && "TCP/IP".equals(nullableText(values, "transport"))) {
      if (hl7Listeners != null) hl7Listeners.stop(connectionId);
    }
  }

  private static String registryKey(String protocol, String connectionId, ObjectNode values) {
    if ("FILE".equals(protocol) && !"HTTP".equals(nullableText(values, "transport"))) {
      return Path.of(requiredText(values, "directory", "FILE directory")).normalize() + "#" + connectionId;
    }
    return "connection:" + connectionId;
  }

  private static int requiredPort(JsonNode values, String field) {
    JsonNode value = values.path(field);
    if (!value.isIntegralNumber() || value.asInt() < 1 || value.asInt() > 65_535) {
      throw new AnalyzerConnectionException(field + " must be a valid TCP port");
    }
    return value.asInt();
  }

  private AnalyzerEntry materialize(String analyzerId, ObjectNode connection, ObjectNode profile, ObjectNode values) {
    AnalyzerEntry entry = new AnalyzerEntry();
    entry.setId(analyzerId);
    entry.setBridgeConnectionId(requiredText(connection, "connectionId", "Connection ID"));
    entry.setProfileId(requiredText(connection.path("profileRef"), "profileId", "Profile ID"));
    entry.setProfileRevision(requiredRevision(connection.path("profileRef"), "revision", "Profile revision"));
    entry.setName(requiredText(connection, "displayName", "Connection name"));
    entry.setExpectedProtocol(requiredText(profile.path("protocol"), "name", "Profile protocol"));
    entry.setInboundTransport(nullableText(values, "transport"));
    entry.setOutboundOrdersSupported(
      profile.path("capabilities").path("outboundOrders").asBoolean(false) &&
      profile.path("communication").path("supports_lis_initiated").asBoolean(false)
    );
    if ("HTTP".equals(entry.getInboundTransport())) {
      String sourceAddress = IpLiteral.canonicalize(requiredText(values, "host", "HTTP analyzer IP address"));
      if (sourceAddress == null) {
        throw new AnalyzerConnectionException("HTTP analyzer host must be a literal IP address");
      }
      entry.setInboundSourceId(sourceAddress);
    } else if (
      "TCP/IP".equals(entry.getInboundTransport()) && "CLIENT".equals(nullableText(values, "connectionRole"))
    ) {
      entry.setInboundSourceId(requiredText(values, "host", "Analyzer host"));
    } else if (
      "TCP/IP".equals(entry.getInboundTransport()) &&
      "SERVER".equals(nullableText(values, "connectionRole")) &&
      "ASTM".equals(entry.getExpectedProtocol())
    ) {
      // The analyzer connects to a shared listener; its connection is resolved per message.
      entry.setListenerPort(requiredPort(values, "port"));
      String host = nullableText(values, "host");
      if (host != null) {
        String address = IpLiteral.canonicalize(host);
        entry.setInboundSourceId(address != null ? address : host.trim());
        // A hostname is kept as entered and never resolved: sender identity is numeric only.
        entry.setInboundAddress(address);
      }
      String senderId = nullableText(values, "senderId");
      entry.setSenderId(senderId == null ? null : senderId.trim());
    }
    entry.setOutboundHost(nullableText(values, "host"));
    entry.setOutboundPort(values.path("port").asInt(0));
    entry.setIdentifierPattern(nullableText(profile, "identifier_pattern"));
    entry.setFilePattern(nullableText(values, "filePattern"));
    entry.setColumnMappings(textMap(profile.path("column_mapping")));
    entry.setFileFormat(nullableText(values, "fileFormat"));
    // Whitespace is meaningful for a delimiter (notably a TSV tab).
    entry.setDelimiter(values.path("delimiter").isTextual() ? values.path("delimiter").asText() : null);
    entry.setSkipRows(values.path("skipRows").asInt(0));
    if ("ASTM".equals(entry.getExpectedProtocol())) {
      entry.setAstmResultRecordSelection(AstmResultRecordSelection.fromProfile(profile.path("configDefaults")));
    } else if ("FILE".equals(entry.getExpectedProtocol())) {
      if (!"HTTP".equals(entry.getInboundTransport())) {
        entry.setFileDirectory(Path.of(requiredText(values, "directory", "FILE directory")).normalize().toString());
      }
      entry.setTabularFileLayout(tabularFileLayout(profile));
      entry.setTabularResultValueSelection(TabularResultValueSelection.fromProfile(profile));
    }

    Set<String> mappedCodes = new LinkedHashSet<>();
    List<String> primaryCodes = new ArrayList<>();
    Map<String, String> codeToLoinc = new LinkedHashMap<>();
    Map<String, List<String>> scannerSynonyms = new LinkedHashMap<>();
    for (JsonNode mapping : profile.path("default_test_mappings")) {
      String code = requiredText(mapping, "test_code", "Profile test code");
      String loinc = requiredText(mapping, "loinc", "Profile test LOINC");
      primaryCodes.add(code);
      List<String> aliases = new ArrayList<>();
      aliases.add(code);
      mapping.path("aliases").forEach(alias -> aliases.add(alias.asText()));
      aliases.forEach(alias -> codeToLoinc.put(alias, loinc));
      mappedCodes.addAll(aliases);
      scannerSynonyms.put(code, List.copyOf(aliases));
    }
    entry.setMappedTestCodes(mappedCodes);
    entry.setCodeToLoinc(codeToLoinc);
    entry.setScannerSynonyms(scannerSynonyms);
    entry.setFileTestCode(fileTestCode(entry.getExpectedProtocol(), profile, primaryCodes, entry.getColumnMappings()));
    entry.setControlResultRecognition(ControlResultRecognition.fromProfile(profile.path("controlResultRecognition")));
    entry.setRecognitionFingerprint(
      requiredText(profile.path("catalog"), "recognitionFingerprint", "Recognition fingerprint")
    );
    return entry;
  }

  private static int requiredRevision(JsonNode node, String field, String label) {
    JsonNode value = node.path(field);
    if (!value.isIntegralNumber() || value.asInt() < 1) {
      throw new AnalyzerConnectionException(label + " is required");
    }
    return value.asInt();
  }

  private static String fileTestCode(
    String protocol,
    ObjectNode profile,
    List<String> primaryCodes,
    Map<String, String> columns
  ) {
    if (!"FILE".equals(protocol)) {
      return null;
    }
    if (primaryCodes.size() == 1) {
      return primaryCodes.get(0);
    }
    if (columns.containsValue("testCode")) {
      return null;
    }
    throw new AnalyzerConnectionException(
      "FILE profile " +
      profile.path("profileMeta").path("id").asText() +
      " must declare a row-level testCode column or one primary test mapping"
    );
  }

  private static TabularFileLayout tabularFileLayout(JsonNode profile) {
    JsonNode detection = profile.path("sheet_detection");
    if (!detection.isObject()) {
      return null;
    }
    List<String> preferredSheetNames = new ArrayList<>();
    detection.path("preferred_sheet_names").forEach(name -> preferredSheetNames.add(name.asText()));
    return TabularFileLayout.headerScan(
      preferredSheetNames,
      detection.path("header_marker").asText(),
      detection.path("max_sheets_to_scan").asInt(),
      detection.path("max_rows_to_scan").asInt()
    );
  }

  private static Map<String, String> textMap(JsonNode value) {
    if (!value.isObject()) {
      return Map.of();
    }
    Map<String, String> result = new LinkedHashMap<>();
    value.fields().forEachRemaining(entry -> result.put(entry.getKey(), entry.getValue().asText()));
    return result;
  }

  private static ObjectNode requiredObject(JsonNode value, String field) {
    JsonNode child = value.path(field);
    if (!(child instanceof ObjectNode object)) {
      throw new AnalyzerConnectionException(field + " must be an object");
    }
    return object;
  }

  private static String requiredText(JsonNode value, String field, String label) {
    String result = nullableText(value, field);
    if (result == null) {
      throw new AnalyzerConnectionException(label + " is required");
    }
    return result;
  }

  private static String nullableText(JsonNode value, String field) {
    JsonNode child = value.path(field);
    return child.isTextual() && !child.asText().isBlank() ? child.asText() : null;
  }
}
