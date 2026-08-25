package org.itech.ahb.connection;

import com.fasterxml.jackson.databind.JsonNode;

/** Complete serial runtime settings read from one pinned analyzer profile revision. */
public record SerialConnectionSettings(
  String protocol,
  int baudRate,
  int dataBits,
  int stopBits,
  String parity,
  String flowControl,
  int readTimeoutMs,
  int messageTimeoutMs,
  int reconnectIntervalMs,
  int maxReconnectAttempts,
  boolean rtsEnabled,
  boolean dtrEnabled
) {

  static SerialConnectionSettings fromProfile(JsonNode profile) {
    JsonNode settings = profile.path("transport_config").path("RS-232");
    return new SerialConnectionSettings(
      requiredText(profile.path("protocol"), "name"),
      requiredInteger(settings, "default_baud_rate"),
      requiredInteger(settings, "data_bits"),
      requiredInteger(settings, "stop_bits"),
      requiredText(settings, "parity"),
      requiredText(settings, "flow_control"),
      requiredInteger(settings, "read_timeout_ms"),
      requiredInteger(settings, "message_timeout_ms"),
      requiredInteger(settings, "reconnect_interval_ms"),
      requiredInteger(settings, "max_reconnect_attempts"),
      requiredBoolean(settings, "rts_enabled"),
      requiredBoolean(settings, "dtr_enabled")
    );
  }

  private static String requiredText(JsonNode parent, String field) {
    JsonNode value = parent.path(field);
    if (!value.isTextual() || value.asText().isBlank()) {
      throw new AnalyzerConnectionException("Pinned profile is missing " + field);
    }
    return value.asText();
  }

  private static int requiredInteger(JsonNode parent, String field) {
    JsonNode value = parent.path(field);
    if (!value.isIntegralNumber()) {
      throw new AnalyzerConnectionException("Pinned profile is missing " + field);
    }
    return value.asInt();
  }

  private static boolean requiredBoolean(JsonNode parent, String field) {
    JsonNode value = parent.path(field);
    if (!value.isBoolean()) {
      throw new AnalyzerConnectionException("Pinned profile is missing " + field);
    }
    return value.asBoolean();
  }
}
