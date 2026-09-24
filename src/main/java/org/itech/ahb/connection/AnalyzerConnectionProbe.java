package org.itech.ahb.connection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.itech.ahb.connectivity.ConnectionProbeExecutor;
import org.itech.ahb.connectivity.ProbeCheck;

/** Runs one non-mutating probe against an exact saved connection revision. */
public final class AnalyzerConnectionProbe {

  private static final int DEFAULT_TIMEOUT_MILLIS = 5_000;

  /** Whether one of the Bridge's own listeners for {@code protocol} is already running on a port. */
  @FunctionalInterface
  public interface BridgeListeners {
    boolean isListening(String protocol, int port);
  }

  private final ObjectMapper objectMapper;
  private final Clock clock;
  private final ConnectionProbeExecutor executor;
  private final BridgeListeners bridgeListeners;

  public AnalyzerConnectionProbe(
    ObjectMapper objectMapper,
    Clock clock,
    ConnectionProbeExecutor executor,
    BridgeListeners bridgeListeners
  ) {
    this.objectMapper = objectMapper;
    this.clock = clock;
    this.executor = executor;
    this.bridgeListeners = bridgeListeners;
  }

  ObjectNode execute(ObjectNode request, ObjectNode connection, ObjectNode profile) {
    String startedAt = clock.instant().toString();
    List<ProbeCheck> checks = checks(connection, profile, (ObjectNode) connection.path("values"));

    ObjectNode result = objectMapper.createObjectNode();
    result.put("schemaVersion", "1.0");
    result.put("requestId", request.path("requestId").asText());
    result.put("connectionId", connection.path("connectionId").asText());
    result.set("profileRef", connection.path("profileRef").deepCopy());
    result.put("configRevision", connection.path("configRevision").asInt());
    result.put("configFingerprint", connection.path("configFingerprint").asText());
    result.put("nonMutating", true);
    result.put("status", overallStatus(checks));
    result.put("startedAt", startedAt);
    result.put("completedAt", clock.instant().toString());
    checks.forEach(check -> result.withArray("checks").add(toContractCheck(check)));
    return result;
  }

  private List<ProbeCheck> checks(ObjectNode connection, ObjectNode profile, ObjectNode values) {
    ProbeCheck bridgeSide = check(connection, profile, values);
    if (!"LISTENER".equals(bridgeSide.kind()) || "MISSING_CONFIGURATION".equals(bridgeSide.status())) {
      return List.of(bridgeSide);
    }
    // The analyzer opens this connection, so the Bridge's listener and the analyzer's reachability
    // are separate questions; without an address the analyzer cannot be checked at all.
    // The analyzer check is reported for information and does not decide the overall status.
    String host = text(values, "host");
    return List.of(
      bridgeSide,
      host == null ? missing("ANALYZER", "analyzer.address.missing") : executor.probeHost(host, timeout(values))
    );
  }

  private ProbeCheck check(ObjectNode connection, ObjectNode profile, ObjectNode values) {
    String protocol = profile.path("protocol").path("name").asText();
    if ("FILE".equals(protocol) && "HTTP".equals(text(values, "transport"))) {
      // An inbound sender is not an endpoint Bridge can actively probe.
      return new ProbeCheck("HTTP_INPUT", "UNSUPPORTED", "http.input.verify.with.delivery", 0, Map.of());
    }
    if ("FILE".equals(protocol)) {
      String directory = text(values, "directory");
      return directory == null
        ? missing("DIRECTORY", "directory.configuration.missing")
        : executor.probeDirectory(directory);
    }

    if ("RS-232".equals(text(values, "transport"))) {
      String serialPort = text(values, "serialPort");
      return serialPort == null
        ? missing("SERIAL_DEVICE", "serial.configuration.missing")
        : executor.probeSerialDevice(serialPort);
    }

    if ("HTTP".equals(protocol)) {
      String baseUrl = text(values, "baseUrl");
      return baseUrl == null
        ? missing("HTTP_ENDPOINT", "http.configuration.missing")
        : executor.probeHttpEndpoint(baseUrl, timeout(values));
    }

    Integer port = port(values.path("port"));
    if ("SERVER".equals(text(values, "connectionRole"))) {
      if (port == null) {
        return missing("LISTENER", "listener.configuration.missing");
      }
      if (currentRuntimeMatchesConfiguration(connection)) {
        ProbeCheck protocolCheck = executor.probeRemote(protocol, "127.0.0.1", port, timeout(values));
        return protocolCheck.status().equals("PASSED")
          ? new ProbeCheck("LISTENER", "PASSED", "listener.ready", protocolCheck.responseTimeMs(), Map.of("port", port))
          : new ProbeCheck(
            "LISTENER",
            protocolCheck.status(),
            protocolCheck.code(),
            protocolCheck.responseTimeMs(),
            protocolCheck.args()
          );
      }
      if (bridgeListeners.isListening(protocol, port)) {
        // A shared or boot listener already serves this port; binding it again would only fail.
        return new ProbeCheck("LISTENER", "PASSED", "listener.ready", 0, Map.of("port", port));
      }
      return executor.probeListener(port);
    }

    String host = text(values, "host");
    return host == null || port == null
      ? missing("REMOTE_PROTOCOL", "remote.configuration.missing")
      : executor.probeRemote(protocol, host, port, timeout(values));
  }

  private static boolean currentRuntimeMatchesConfiguration(ObjectNode connection) {
    JsonNode active = connection.path("activeRuntimeRef");
    return (
      "ACTIVE".equals(connection.path("actualRuntimeState").asText()) &&
      active.isObject() &&
      active.path("profileRef").equals(connection.path("profileRef")) &&
      active.path("configFingerprint").asText().equals(connection.path("configFingerprint").asText())
    );
  }

  private ObjectNode toContractCheck(ProbeCheck check) {
    ObjectNode result = objectMapper.createObjectNode();
    result.put("key", check.kind().toLowerCase(Locale.ROOT).replace('_', '-'));
    result.put("status", contractStatus(check.status()));
    result.put("messageKey", check.code());
    result.put("durationMillis", Math.max(0, check.responseTimeMs()));
    if (!check.args().isEmpty()) {
      ObjectNode details = result.putObject("details");
      check.args().forEach((key, value) -> putScalar(details, key, value));
    }
    return result;
  }

  /**
   * The worst of the Bridge-side checks. The analyzer check on a connection the analyzer opens is
   * advisory: a firewalled or NAT'd analyzer that works still fails a reachability test, and many
   * profile revisions offer no address for it at all.
   */
  private static String overallStatus(List<ProbeCheck> checks) {
    int worst = checks
      .stream()
      .filter(check -> !"ANALYZER".equals(check.kind()))
      .mapToInt(check -> switch (check.status()) {
        case "PASSED" -> 0;
        case "MISSING_CONFIGURATION" -> 1;
        case "TIMED_OUT" -> 2;
        default -> 3;
      })
      .max()
      .orElse(0);
    return switch (worst) {
      case 0 -> "SUCCEEDED";
      case 1 -> "BLOCKED";
      case 2 -> "TIMEOUT";
      default -> "FAILED";
    };
  }

  private static String contractStatus(String status) {
    return switch (status) {
      case "PASSED" -> "PASSED";
      case "MISSING_CONFIGURATION" -> "SKIPPED";
      default -> "FAILED";
    };
  }

  private static ProbeCheck missing(String kind, String code) {
    return new ProbeCheck(kind, "MISSING_CONFIGURATION", code, 0, Map.of());
  }

  private static String text(JsonNode values, String key) {
    JsonNode value = values.path(key);
    return value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
  }

  private static Integer port(JsonNode value) {
    return value.isIntegralNumber() && value.asInt() >= 1 && value.asInt() <= 65_535 ? value.asInt() : null;
  }

  private static int timeout(JsonNode values) {
    JsonNode value = values.path("connectTimeoutMillis");
    return value.isIntegralNumber() && value.asInt() > 0 ? value.asInt() : DEFAULT_TIMEOUT_MILLIS;
  }

  private static void putScalar(ObjectNode target, String key, Object value) {
    if (value instanceof Integer integer) {
      target.put(key, integer);
    } else if (value instanceof Long longValue) {
      target.put(key, longValue);
    } else if (value instanceof Number number) {
      target.put(key, number.doubleValue());
    } else if (value instanceof Boolean booleanValue) {
      target.put(key, booleanValue);
    } else {
      target.put(key, String.valueOf(value));
    }
  }
}
