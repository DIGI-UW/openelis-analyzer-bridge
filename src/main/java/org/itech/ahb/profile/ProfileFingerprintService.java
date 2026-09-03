package org.itech.ahb.profile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.erdtman.jcs.JsonCanonicalizer;

/** Produces stable analyzer-profile identities using RFC 8785 canonical JSON. */
public final class ProfileFingerprintService {

  public String revisionFingerprint(JsonNode profile) {
    if (!(profile instanceof ObjectNode object)) {
      throw new IllegalArgumentException("Fingerprint input must be a JSON object");
    }
    ObjectNode input = object.deepCopy();
    JsonNode catalog = input.path("catalog");
    if (catalog instanceof ObjectNode metadata) {
      metadata.remove("revisionFingerprint");
    }
    return canonicalFingerprint(input);
  }

  public String recognitionFingerprint(JsonNode recognition) {
    return fingerprintWithout(recognition, "recognitionFingerprint");
  }

  public String canonicalFingerprint(JsonNode value) {
    try {
      byte[] canonicalJson = new JsonCanonicalizer(value.toString()).getEncodedUTF8();
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonicalJson);
      return "sha256:" + HexFormat.of().formatHex(digest);
    } catch (IOException | NoSuchAlgorithmException e) {
      throw new IllegalArgumentException("Cannot fingerprint canonical JSON", e);
    }
  }

  private String fingerprintWithout(JsonNode source, String selfField) {
    if (!(source instanceof ObjectNode object)) {
      throw new IllegalArgumentException("Fingerprint input must be a JSON object");
    }

    ObjectNode input = object.deepCopy();
    input.remove(selfField);
    return canonicalFingerprint(input);
  }
}
