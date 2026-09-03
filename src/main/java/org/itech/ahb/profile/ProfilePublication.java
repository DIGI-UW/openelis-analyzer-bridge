package org.itech.ahb.profile;

import java.time.Instant;
import java.util.Objects;

public record ProfilePublication(ProfileAuditAction action, String actor, Instant markedAt) {
  public ProfilePublication {
    Objects.requireNonNull(action, "action");
    Objects.requireNonNull(actor, "actor");
    Objects.requireNonNull(markedAt, "markedAt");
  }
}
