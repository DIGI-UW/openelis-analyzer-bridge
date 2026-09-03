package org.itech.ahb.profile;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record ProfileDraft(
  String draftId,
  ProfileDraftKind kind,
  ObjectNode profile,
  String baseProfileId,
  Integer baseRevision,
  String createdBy,
  Instant createdAt,
  String updatedBy,
  Instant updatedAt,
  List<String> validationIssues
) {
  public ProfileDraft {
    Objects.requireNonNull(draftId, "draftId");
    Objects.requireNonNull(kind, "kind");
    profile = Objects.requireNonNull(profile, "profile").deepCopy();
    Objects.requireNonNull(createdBy, "createdBy");
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(updatedBy, "updatedBy");
    Objects.requireNonNull(updatedAt, "updatedAt");
    validationIssues = List.copyOf(validationIssues);
  }

  @Override
  public ObjectNode profile() {
    return profile.deepCopy();
  }

  ProfileDraft withProfile(ObjectNode updatedProfile, String actor, Instant markedAt, List<String> issues) {
    return new ProfileDraft(
      draftId,
      kind,
      updatedProfile,
      baseProfileId,
      baseRevision,
      createdBy,
      createdAt,
      actor,
      markedAt,
      issues
    );
  }

  ProfileDraft withValidationIssues(List<String> issues) {
    return new ProfileDraft(
      draftId,
      kind,
      profile,
      baseProfileId,
      baseRevision,
      createdBy,
      createdAt,
      updatedBy,
      updatedAt,
      issues
    );
  }
}
