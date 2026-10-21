package org.itech.ahb.profile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The codings a profile test entry names its test by: {@code loinc} is shorthand for a LOINC entry
 * in {@code codings}, which may cite any system, a distro's own included.
 */
public final class TestCodings {

  public static final String LOINC_SYSTEM = "http://loinc.org";

  private TestCodings() {}

  /** Every LOINC code the entry names, from {@code loinc} and from its LOINC codings. */
  static Set<String> loincCodes(JsonNode mapping) {
    Set<String> codes = new LinkedHashSet<>();
    String loinc = mapping.path("loinc").asText("");
    if (!loinc.isEmpty()) {
      codes.add(loinc);
    }
    for (JsonNode coding : mapping.path("codings")) {
      if (LOINC_SYSTEM.equals(coding.path("system").asText())) {
        codes.add(coding.path("code").asText(""));
      }
    }
    return codes;
  }

  /**
   * Moves each test's {@code normalized_coding}, which contract 1.0 allowed and the current contract
   * retires, into its {@code codings}, keeping its display, so a revision saved under 1.0 still
   * loads. True when the profile changed.
   */
  public static boolean foldRetiredNormalizedCoding(ObjectNode profile) {
    boolean changed = false;
    for (JsonNode mapping : profile.path("default_test_mappings")) {
      if (!(mapping instanceof ObjectNode test) || !test.has("normalized_coding")) {
        continue;
      }
      JsonNode retired = test.remove("normalized_coding");
      changed = true;
      if (!retired.isObject() || !retired.hasNonNull("system") || !retired.hasNonNull("code")) {
        continue;
      }
      ArrayNode codings = test.has("codings") && test.get("codings").isArray()
        ? (ArrayNode) test.get("codings")
        : test.putArray("codings");
      ObjectNode same = null;
      for (JsonNode coding : codings) {
        if (
          coding.path("system").asText().equals(retired.path("system").asText()) &&
          coding.path("code").asText().equals(retired.path("code").asText())
        ) {
          same = (ObjectNode) coding;
        }
      }
      if (same == null) {
        codings.add(retired.deepCopy());
      } else if (!same.hasNonNull("display") && retired.hasNonNull("display")) {
        same.set("display", retired.get("display"));
      }
    }
    return changed;
  }

  /** The entry's LOINC code, or null when it names its test by other codings only. */
  public static String loinc(JsonNode mapping) {
    return loincCodes(mapping).stream().findFirst().orElse(null);
  }
}
