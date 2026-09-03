package org.itech.ahb.connection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.util.Locale;
import java.util.Map;
import org.itech.ahb.connectivity.ConnectionProbeExecutor;
import org.itech.ahb.connectivity.ProbeCheck;

/** Runs one non-mutating probe against an exact saved connection revision. */
public final class AnalyzerConnectionProbe {

  private static final int DEFAULT_TIMEOUT_MILLIS = 5_000;

  private final ObjectMapper objectMapper;
  private final Clock clock;
  private final ConnectionProbeExecutor executor;

  public AnalyzerConnectionProbe(
    ObjectMapper objectMapper,
    Clock clock,
    ConnectionProbeExecutor executor
  ) {
    this.objectMapper = objectMapper;
    this.clock = clock;
    this.executor = executor;
  }

  ObjectNode execute(ObjectNode request, ObjectNode connection, ObjectNode profile) {
    String startedAt = clock.instant().toString();
    ProbeCheck check = check(profile, (ObjectNode) connection.path("values"));

    ObjectNode result = objectMapper.createObjectNode();
    result.put("schemaVersion", "1.0");
    result.put("requestId", request.path("requestId").asText());
    result.put("connectionId", connection.path("connectionId").asText());
    result.set("profileRef", connection.path("profileRef").deepCopy());
    result.put("configRevision", connection.path("configRevision").asInt());
    result.put("configFingerprint", connection.path("configFingerprint").asText());
    result.put("nonMutating", true);
    result.put("status", overallStatus(check));
    result.put("startedAt", startedAt);
    result.put("completedAt", clock.instant().toString());
    result.putArray("checks").add(toContractCheck(check));
    return result;
  }

  private ProbeCheck check(ObjectNode profile, ObjectNode values) {
    String protocol = profile.path("protocol").path("name").asText();
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
      return port == null
        ? missing("LISTENER", "listener.configuration.missing")
        : executor.probeListener(port);
    }

    String host = text(values, "host");
    return host == null || port == null
      ? missing("REMOTE_PROTOCOL", "remote.configuration.missing")
      : executor.probeRemote(protocol, host, port, timeout(values));
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

  private static String overallStatus(ProbeCheck check) {
    return switch (check.status()) {
      case "PASSED" -> "SUCCEEDED";
      case "TIMED_OUT" -> "TIMEOUT";
      case "MISSING_CONFIGURATION" -> "BLOCKED";
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
    return value.isIntegralNumber() && value.asInt() >= 1 && value.asInt() <= 65_535
      ? value.asInt()
      : null;
  }

  private static int timeout(JsonNode values) {
    JsonNode value = values.path("connectTimeoutMillis");
    return value.isIntegralNumber() && value.asInt() > 0
      ? value.asInt()
      : DEFAULT_TIMEOUT_MILLIS;
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
