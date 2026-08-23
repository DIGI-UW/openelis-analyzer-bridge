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

/** Builds versioned probe evidence from one registered analyzer candidate. */
public final class AnalyzerConnectionProbeService {

  private static final int REMOTE_TIMEOUT_MS = 5000;

  private final AnalyzerRegistryConfig registry;
  private final ConnectionProbeExecutor executor;
  private final String advertisedHost;

  public AnalyzerConnectionProbeService(
    AnalyzerRegistryConfig registry,
    ConnectionProbeExecutor executor,
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
    Integer listenPort = integerSetting(analyzer, "listenPort");
    ProbeCheck listenerCheck;
    if (listenPort == null) {
      listenerCheck = missing("LISTENER", "listener.port.missing");
    } else {
      listenerCheck = executor.probeListener(listenPort);
    }
    checks.add(listenerCheck);

    if ("TWO_WAY".equals(analyzer.getDataFlow())) {
      String remoteHost = textSetting(analyzer, "remoteHost");
      Integer remotePort = integerSetting(analyzer, "remotePort");
      if (remoteHost == null || remotePort == null) {
        checks.add(missing("REMOTE_PROTOCOL", "remote.configuration.missing"));
      } else {
        checks.add(
          executor.probeRemote(
            analyzer.getExpectedProtocol(),
            remoteHost,
            remotePort,
            REMOTE_TIMEOUT_MS
          )
        );
      }
    }

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
    putConfigureEndpoint(result, listenPort);
    result.put(
      "resultsOnlyAvailable",
      "TWO_WAY".equals(analyzer.getDataFlow()) &&
      "PASSED".equals(listenerCheck.status()) &&
      checks.stream().anyMatch(check -> !"PASSED".equals(check.status()))
    );
    ArrayNode checkNodes = result.putArray("checks");
    checks.forEach(check -> checkNodes.add(toJson(check)));
    return result;
  }

  private void putConfigureEndpoint(ObjectNode result, Integer listenPort) {
    if (listenPort == null || advertisedHost == null || advertisedHost.isBlank()) {
      result.putNull("configureEndpoint");
      return;
    }
    ObjectNode endpoint = result.putObject("configureEndpoint");
    endpoint.put("kind", "NETWORK");
    endpoint.put("host", advertisedHost);
    endpoint.put("port", listenPort);
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
}
