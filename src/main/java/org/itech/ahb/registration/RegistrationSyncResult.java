package org.itech.ahb.registration;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

public record RegistrationSyncResult(
  String schemaVersion,
  String appliedStateRevision,
  Counts counts,
  List<Registration> registrations,
  List<String> errors
) {
  public RegistrationSyncResult {
    registrations = List.copyOf(registrations);
    errors = List.copyOf(errors);
  }

  public record Counts(int total, int added, int updated, int removed, int unchanged, int rejected) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Registration(String oeAnalyzerId, Status status, String message) {}

  public enum Status {
    APPLIED,
    UNCHANGED,
    REJECTED,
  }
}
