package org.itech.ahb.profile;

import com.fasterxml.jackson.databind.JsonNode;

/** Pinned profile policy for the SPM segment belonging to an observation. */
public enum Hl7SpecimenPosition {
  PRECEDING,
  FOLLOWING_OBX;

  public static Hl7SpecimenPosition fromProfile(JsonNode configDefaults) {
    JsonNode value = configDefaults.path("extractionOverrides").path("specimenPosition");
    if (value.isMissingNode()) return PRECEDING;
    if (!value.isTextual()) throw new IllegalArgumentException("HL7 specimenPosition must be a string");
    try {
      return valueOf(value.textValue());
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("HL7 specimenPosition must be PRECEDING or FOLLOWING_OBX", exception);
    }
  }
}
