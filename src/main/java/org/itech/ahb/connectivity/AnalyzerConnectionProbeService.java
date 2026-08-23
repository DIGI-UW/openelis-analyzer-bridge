package org.itech.ahb.connectivity;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.itech.ahb.config.AnalyzerRegistryConfig;
import org.itech.ahb.config.AnalyzerRegistryConfig.AnalyzerEntry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Builds versioned probe evidence from one registered analyzer candidate. */
@Service
public final class AnalyzerConnectionProbeService {

  private static final int REMOTE_TIMEOUT_MS = 5000;

  private final AnalyzerRegistryConfig registry;
  private final ConnectionProbeExecutor executor;
  private final String advertisedHost;

  public AnalyzerConnectionProbeService(
    AnalyzerRegistryConfig registry,
    ConnectionProbeExecutor executor,
    @Value("${bridge.connectivity.advertised-host:}")
    String advertisedHost
  ) {
    this.registry = registry;
    this.executor = executor;
    this.advertisedHost = advertisedHost;
  }

  public Optional<ObjectNode> probe(String analyzerId) {
    return findAnalyzer(analyzerId).map(this::probe);
  }

  private Optional<AnalyzerEntry> findAnalyzer(String analyzerId) {
    if (analyzerId == null || analyzerId.isBlank()) {
      return Optional.empty();
    }
    return registry
      .getRegisteredAnalyzers()
      .values()
      .stream()
      .filter(entry -> analyzerId.equals(entry.getId()))
      .findFirst();
  }

  private ObjectNode probe(AnalyzerEntry analyzer) {
    List<ProbeCheck> checks = new ArrayList<>();
    Endpoint endpoint = switch (analyzer.getConnectionMode()) {
      case "FILE" -> probeDirectory(analyzer, checks);
      case "SERIAL" -> probeSerialDevice(analyzer, checks);
      case "HTTP" -> probeHttpEndpoint(analyzer, checks);
      case "TCP", "MLLP" -> probeNetwork(analyzer, checks);
      default -> {
        checks.add(missing("REMOTE_PROTOCOL", "connection.mode.unsupported"));
        yield null;
      }
    };

    ObjectNode result = JsonNodeFactory.instance.objectNode();
    result.put("schemaVersion", "1.0");
    result.put("analyzerId", analyzer.getId());
    ObjectNode profileRef = result.putObject("profileRef");
    profileRef.put("profileId", analyzer.getProfileId());
    profileRef.put("revision", analyzer.getProfileRevision());
    result.put("desiredStateFingerprint", analyzer.getDesiredStateFingerprint());
    ObjectNode connection = result.putObject("connection");
    connection.put("mode", analyzer.getConnectionMode());
    connection.put("role", analyzer.getConnectionRole());
    result.put("dataFlow", analyzer.getDataFlow());
    result.put("outcome", outcome(checks));
    putConfigureEndpoint(result, endpoint);
    result.put(
      "resultsOnlyAvailable",
      resultsOnlyAvailable(analyzer, checks)
    );
    ArrayNode checkNodes = result.putArray("checks");
    checks.forEach(check -> checkNodes.add(toJson(check)));
    return result;
  }

  private Endpoint probeNetwork(AnalyzerEntry analyzer, List<ProbeCheck> checks) {
    if ("INITIATOR".equals(analyzer.getConnectionRole())) {
      return probeRemote(analyzer, checks);
    }

    Integer listenPort = integerSetting(analyzer, "listenPort");
    Endpoint endpoint = null;
    if (advertisedHost == null || advertisedHost.isBlank()) {
      checks.add(missing("LISTENER", "listener.endpoint.missing"));
    } else if (listenPort == null) {
      checks.add(missing("LISTENER", "listener.port.missing"));
    } else {
      checks.add(executor.probeListener(listenPort));
      endpoint = Endpoint.network(advertisedHost, listenPort);
    }

    if ("TWO_WAY".equals(analyzer.getDataFlow())) {
      probeRemote(analyzer, checks);
    }
    return endpoint;
  }

  private Endpoint probeRemote(AnalyzerEntry analyzer, List<ProbeCheck> checks) {
    String remoteHost = textSetting(analyzer, "remoteHost");
    Integer remotePort = integerSetting(analyzer, "remotePort");
    if (remoteHost == null || remotePort == null) {
      checks.add(missing("REMOTE_PROTOCOL", "remote.configuration.missing"));
      return null;
    }
    checks.add(
      executor.probeRemote(
        analyzer.getExpectedProtocol(),
        remoteHost,
        remotePort,
        timeoutSetting(analyzer)
      )
    );
    return Endpoint.network(remoteHost, remotePort);
  }

  private Endpoint probeDirectory(AnalyzerEntry analyzer, List<ProbeCheck> checks) {
    String directory = textSetting(analyzer, "directory");
    if (directory == null) {
      checks.add(missing("DIRECTORY", "directory.configuration.missing"));
      return null;
    }
    checks.add(executor.probeDirectory(directory));
    return Endpoint.path("DIRECTORY", directory);
  }

  private Endpoint probeSerialDevice(AnalyzerEntry analyzer, List<ProbeCheck> checks) {
    String device = textSetting(analyzer, "device");
    if (device == null) {
      checks.add(missing("SERIAL_DEVICE", "serial.configuration.missing"));
      return null;
    }
    checks.add(executor.probeSerialDevice(device));
    return Endpoint.path("DEVICE", device);
  }

  private Endpoint probeHttpEndpoint(AnalyzerEntry analyzer, List<ProbeCheck> checks) {
    String baseUrl = textSetting(analyzer, "baseUrl");
    if (baseUrl == null) {
      checks.add(missing("HTTP_ENDPOINT", "http.configuration.missing"));
      return null;
    }
    checks.add(executor.probeHttpEndpoint(baseUrl, timeoutSetting(analyzer)));
    return Endpoint.http(baseUrl);
  }

  private static boolean resultsOnlyAvailable(
    AnalyzerEntry analyzer,
    List<ProbeCheck> checks
  ) {
    if (
      !"TWO_WAY".equals(analyzer.getDataFlow()) ||
      !"RECEIVER".equals(analyzer.getConnectionRole()) ||
      !("TCP".equals(analyzer.getConnectionMode()) ||
        "MLLP".equals(analyzer.getConnectionMode()))
    ) {
      return false;
    }
    boolean listenerPassed = checks
      .stream()
      .anyMatch(
        check -> "LISTENER".equals(check.kind()) && "PASSED".equals(check.status())
      );
    boolean remoteFailed = checks
      .stream()
      .anyMatch(
        check ->
          "REMOTE_PROTOCOL".equals(check.kind()) &&
          !"PASSED".equals(check.status())
      );
    return listenerPassed && remoteFailed;
  }

  private static void putConfigureEndpoint(ObjectNode result, Endpoint endpoint) {
    if (endpoint == null) {
      result.putNull("configureEndpoint");
      return;
    }
    ObjectNode node = result.putObject("configureEndpoint");
    node.put("kind", endpoint.kind());
    if (endpoint.host() != null) {
      node.put("host", endpoint.host());
      node.put("port", endpoint.port());
    } else if (endpoint.path() != null) {
      node.put("path", endpoint.path());
    } else {
      node.put("url", endpoint.url());
    }
  }

  private static ObjectNode toJson(ProbeCheck check) {
    ObjectNode node = JsonNodeFactory.instance.objectNode();
    node.put("kind", check.kind());
    node.put("status", check.status());
    node.put("code", check.code());
    node.put("responseTimeMs", check.responseTimeMs());
    if (!check.args().isEmpty()) {
      ObjectNode args = node.putObject("args");
      check.args().forEach((name, value) -> putScalar(args, name, value));
    }
    return node;
  }

  private static void putScalar(ObjectNode node, String name, Object value) {
    if (value instanceof Integer integer) {
      node.put(name, integer);
    } else if (value instanceof Long longValue) {
      node.put(name, longValue);
    } else if (value instanceof Number number) {
      node.put(name, number.doubleValue());
    } else if (value instanceof Boolean booleanValue) {
      node.put(name, booleanValue);
    } else {
      node.put(name, String.valueOf(value));
    }
  }

  private static String outcome(List<ProbeCheck> checks) {
    if (checks.stream().anyMatch(check -> "MISSING_CONFIGURATION".equals(check.status()))) {
      return "MISSING_CONFIGURATION";
    }
    if (checks.stream().anyMatch(check -> "TIMED_OUT".equals(check.status()))) {
      return "TIMEOUT";
    }
    if (checks.stream().anyMatch(check -> "FAILED".equals(check.status()))) {
      return "FAILURE";
    }
    return "SUCCESS";
  }

  private static ProbeCheck missing(String kind, String code) {
    return new ProbeCheck(kind, "MISSING_CONFIGURATION", code, 0, Map.of());
  }

  private static String textSetting(AnalyzerEntry analyzer, String name) {
    Object value = analyzer.getConnectionSettings().get(name);
    if (!(value instanceof String text) || text.isBlank()) {
      return null;
    }
    return text;
  }

  private static Integer integerSetting(AnalyzerEntry analyzer, String name) {
    Object value = analyzer.getConnectionSettings().get(name);
    if (!(value instanceof Number number)) {
      return null;
    }
    int result = number.intValue();
    return result > 0 && result <= 65535 ? result : null;
  }

  private static int timeoutSetting(AnalyzerEntry analyzer) {
    Object value = analyzer.getConnectionSettings().get("connectTimeoutMillis");
    if (value instanceof Number number && number.intValue() > 0) {
      return number.intValue();
    }
    return REMOTE_TIMEOUT_MS;
  }

  private record Endpoint(
    String kind,
    String host,
    Integer port,
    String path,
    String url
  ) {

    private static Endpoint network(String host, int port) {
      return new Endpoint("NETWORK", host, port, null, null);
    }

    private static Endpoint path(String kind, String path) {
      return new Endpoint(kind, null, null, path, null);
    }

    private static Endpoint http(String url) {
      return new Endpoint("HTTP", null, null, null, url);
    }
  }
}
