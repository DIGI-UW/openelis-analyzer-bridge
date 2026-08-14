package org.itech.ahb.profile;

import java.util.Locale;

public record ProfileCatalogFilter(String query, String source, String status, String protocol) {
  public static ProfileCatalogFilter all() {
    return new ProfileCatalogFilter(null, null, null, null);
  }

  boolean matches(ProfileCatalogEntry entry) {
    var profile = entry.profile();
    return (
      contains(profile.path("displayName").asText(), query) &&
      equals(profile.path("source").asText(), source) &&
      equals(profile.path("status").asText(), status) &&
      equals(profile.path("protocol").asText(), protocol)
    );
  }

  private static boolean contains(String candidate, String expected) {
    return (
      expected == null ||
      expected.isBlank() ||
      candidate.toLowerCase(Locale.ROOT).contains(expected.trim().toLowerCase(Locale.ROOT))
    );
  }

  private static boolean equals(String candidate, String expected) {
    return expected == null || expected.isBlank() || candidate.equalsIgnoreCase(expected.trim());
  }
}
