package org.itech.ahb.profile;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * How the pinned profile reads a result: where each part of a record sits, which raw values mean
 * the run produced nothing, and how the instrument writes a number. It describes the instrument,
 * never a site; a connection may override the number format because an instrument runs in the
 * locale its lab set.
 */
public record ResultReading(
  AstmResultParts astmParts,
  char decimalSeparator,
  Map<String, Map<String, Set<String>>> runFailures,
  Map<String, String> profileCodeByInstrumentCode
) {
  public ResultReading {
    runFailures = runFailures == null ? Map.of() : runFailures;
    profileCodeByInstrumentCode = profileCodeByInstrumentCode == null ? Map.of() : profileCodeByInstrumentCode;
  }

  public static ResultReading fromProfile(JsonNode profile) {
    return fromProfile(profile, null);
  }

  /**
   * @param connectionValues the saved connection's values, which may carry a {@code numberFormat}
   *     that replaces the profile's
   */
  public static ResultReading fromProfile(JsonNode profile, JsonNode connectionValues) {
    JsonNode configDefaults = profile.path("configDefaults");
    String configured = configDefaults.path("numberFormat").asText(".");
    Map<String, String> profileCodeByInstrumentCode = new HashMap<>();
    if (connectionValues != null) {
      if (connectionValues.path("numberFormat").isTextual()) {
        configured = connectionValues.path("numberFormat").asText();
      }
      connectionValues
        .path("codeOverrides")
        .fields()
        .forEachRemaining(entry -> profileCodeByInstrumentCode.put(entry.getValue().asText(), entry.getKey()));
    }
    Map<String, Map<String, Set<String>>> runFailures = new HashMap<>();
    for (JsonNode mapping : profile.path("default_test_mappings")) {
      Map<String, Set<String>> bySubIdentity = new HashMap<>();
      addAll(bySubIdentity, "", mapping.path("run_failure_values"));
      for (JsonNode component : mapping.path("components")) {
        addAll(bySubIdentity, component.path("sub_identity").asText(""), component.path("run_failure_values"));
      }
      if (bySubIdentity.isEmpty()) {
        continue;
      }
      runFailures.put(mapping.path("test_code").asText(), bySubIdentity);
      mapping.path("aliases").forEach(alias -> runFailures.put(alias.asText(), bySubIdentity));
    }
    return new ResultReading(
      AstmResultParts.fromProfile(configDefaults),
      configured.charAt(0),
      runFailures,
      profileCodeByInstrumentCode
    );
  }

  /** The profile's code for a code the instrument sent: the instrument's own code when a connection overrides it. */
  public String profileCode(String instrumentCode) {
    return profileCodeByInstrumentCode.getOrDefault(instrumentCode, instrumentCode);
  }

  /** A number as the instrument wrote it, in the plain decimal form a FHIR decimal takes. */
  public String canonicalNumber(String text) {
    return decimalSeparator == '.' ? text : text.replace(decimalSeparator, '.');
  }

  /** Whether this raw value, on this record, says the run produced no result. */
  public boolean isRunFailure(String code, String subIdentity, String value) {
    return runFailures.getOrDefault(code, Map.of()).getOrDefault(subIdentity, Set.of()).contains(value);
  }

  private static void addAll(Map<String, Set<String>> bySubIdentity, String subIdentity, JsonNode values) {
    if (values.isArray() && !values.isEmpty()) {
      Set<String> failures = bySubIdentity.computeIfAbsent(subIdentity, key -> new HashSet<>());
      values.forEach(value -> failures.add(value.asText()));
    }
  }
}
