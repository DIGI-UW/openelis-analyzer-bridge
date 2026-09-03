package org.itech.ahb.fhir;

import java.util.List;

/** Profile-owned instructions for locating a tabular result header. */
public record TabularFileLayout(
  List<String> preferredSheetNames,
  String headerMarker,
  int maxSheetsToScan,
  int maxRowsToScan
) {
  public TabularFileLayout {
    preferredSheetNames = preferredSheetNames == null ? List.of() : List.copyOf(preferredSheetNames);
    if (headerMarker == null || headerMarker.isBlank()) {
      throw new IllegalArgumentException("headerMarker is required");
    }
    if (maxSheetsToScan < 1) {
      throw new IllegalArgumentException("maxSheetsToScan must be positive");
    }
    if (maxRowsToScan < 1) {
      throw new IllegalArgumentException("maxRowsToScan must be positive");
    }
  }

  public static TabularFileLayout headerScan(
    List<String> preferredSheetNames,
    String headerMarker,
    int maxSheetsToScan,
    int maxRowsToScan
  ) {
    return new TabularFileLayout(preferredSheetNames, headerMarker, maxSheetsToScan, maxRowsToScan);
  }
}
