package org.itech.ahb.profile;

import com.fasterxml.jackson.databind.JsonNode;

public record ProfileCatalogEntry(JsonNode profile, ProfileAuditEvent audit, String fingerprint) {}
