package org.itech.ahb.profile;

import com.fasterxml.jackson.databind.JsonNode;
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
    String loinc = mapping.path("loinc").asText("").trim();
    if (!loinc.isEmpty()) {
      codes.add(loinc);
    }
    for (JsonNode coding : mapping.path("codings")) {
      if (LOINC_SYSTEM.equals(coding.path("system").asText())) {
        codes.add(coding.path("code").asText("").trim());
      }
    }
    return codes;
  }

  /** The entry's LOINC code, or null when it names its test by other codings only. */
  public static String loinc(JsonNode mapping) {
    return loincCodes(mapping).stream().findFirst().orElse(null);
  }
}
