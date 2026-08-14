package org.itech.ahb.profile;

import java.time.Instant;

public record ProfileAuditEvent(ProfileAuditAction action, String actor, Instant markedAt) {}
