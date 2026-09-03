package org.itech.ahb.profile;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Objects;

public final class ProfileRevision {

  private final ObjectNode profile;
  private final ProfilePublication publication;

  public ProfileRevision(ObjectNode profile, ProfilePublication publication) {
    this.profile = Objects.requireNonNull(profile, "profile").deepCopy();
    this.publication = Objects.requireNonNull(publication, "publication");
  }

  @JsonProperty("profile")
  public ObjectNode profile() {
    return profile.deepCopy();
  }

  @JsonProperty("publication")
  public ProfilePublication publication() {
    return publication;
  }

  @Override
  public boolean equals(Object candidate) {
    if (this == candidate) {
      return true;
    }
    if (!(candidate instanceof ProfileRevision other)) {
      return false;
    }
    return profile.equals(other.profile) && publication.equals(other.publication);
  }

  @Override
  public int hashCode() {
    return Objects.hash(profile, publication);
  }
}
