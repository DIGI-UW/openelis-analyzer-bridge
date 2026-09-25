package org.itech.ahb.connection;

import com.fasterxml.jackson.databind.JsonNode;

/** Shared outbound endpoint semantics for activation and non-mutating connection tests. */
public final class AnalyzerOutboundEndpoint {

  private final AnalyzerOutboundDefaults defaults;

  public AnalyzerOutboundEndpoint(AnalyzerOutboundDefaults defaults) {
    this.defaults = defaults;
  }

  public record ResolvedPort(int port, String source) {}

  public static boolean ordersEnabled(JsonNode profile, JsonNode values) {
    return (
      profile.path("capabilities").path("outboundOrders").asBoolean(false) &&
      profile.path("communication").path("supports_lis_initiated").asBoolean(false) &&
      (!values.has("dataFlow") || "TWO_WAY".equals(values.path("dataFlow").asText()))
    );
  }

  public static boolean needed(JsonNode profile, JsonNode values) {
    String protocol = profile.path("protocol").path("name").asText();
    String transport = values.path("transport").asText();
    return (
      ("ASTM".equals(protocol) || "HL7".equals(protocol)) &&
      ("TCP/IP".equals(transport) || "MLLP".equals(transport)) &&
      ("CLIENT".equals(values.path("connectionRole").asText()) || ordersEnabled(profile, values))
    );
  }

  public ResolvedPort resolvePort(JsonNode profile, JsonNode values) {
    String mode = values.path("outboundPortMode").asText("");
    if (!mode.isEmpty() && !"DEFAULT".equals(mode) && !"OVERRIDE".equals(mode)) {
      throw new AnalyzerConnectionException("outboundPortMode must be DEFAULT or OVERRIDE");
    }
    if (!"DEFAULT".equals(mode)) {
      if (values.has("outboundPort")) return resolved(values.path("outboundPort"), "Connection override");
      // Historical SERVER port values were inbound listener settings. They are never destinations.
      if ("CLIENT".equals(values.path("connectionRole").asText()) && values.has("port")) {
        return resolved(values.path("port"), "Connection override");
      }
    }
    JsonNode transport = profile.path("transport_config").path(values.path("transport").asText());
    if (transport.has("default_port")) return resolved(transport.path("default_port"), "Profile default");
    // In older CLIENT-only defaults, port already represented the remote endpoint.
    JsonNode profileDefaults = profile.path("configDefaults");
    if ("CLIENT".equals(profileDefaults.path("connectionRole").asText()) && profileDefaults.has("port")) {
      return resolved(profileDefaults.path("port"), "Profile default");
    }
    int port =
      switch (profile.path("protocol").path("name").asText()) {
        case "ASTM" -> defaults.getAstmPort();
        case "HL7" -> defaults.getHl7Port();
        default -> throw new AnalyzerConnectionException("No outbound port fallback for this protocol");
      };
    if (port < 1 || port > 65535) throw new AnalyzerConnectionException(
      "Bridge outbound default port must be between 1 and 65535"
    );
    return new ResolvedPort(port, "Bridge default");
  }

  private static ResolvedPort resolved(JsonNode value, String source) {
    if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 1 || value.intValue() > 65535) {
      throw new AnalyzerConnectionException(source + " port must be an integer between 1 and 65535");
    }
    return new ResolvedPort(value.intValue(), source);
  }
}
