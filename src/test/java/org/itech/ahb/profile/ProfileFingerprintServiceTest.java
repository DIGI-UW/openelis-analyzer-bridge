package org.itech.ahb.profile;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class ProfileFingerprintServiceTest {

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final ProfileFingerprintService service = new ProfileFingerprintService();

  @Test
  void usesRfc8785CanonicalJsonAndExcludesTheNestedRevisionFingerprint() throws Exception {
    JsonNode profile = objectMapper.readTree(
      """
      {
        "b": 1,
        "catalog": {
          "revisionFingerprint": "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        },
        "a": 56.0
      }
      """
    );

    assertThat(service.revisionFingerprint(profile)).isEqualTo(
      "sha256:8115d8ba866f1d8b7c8442fecd3fbc84a068489f9e9cfe7a04754a8144806a99"
    );
  }

  @Test
  void propertyOrderAndSuppliedFingerprintCannotChangeRevisionIdentity() throws Exception {
    JsonNode first = objectMapper.readTree(
      """
      {
        "profileMeta": { "id": "site.example" },
        "catalog": {
          "revision": 1,
          "status": "ACTIVE",
          "revisionFingerprint": "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        }
      }
      """
    );
    JsonNode reordered = objectMapper.readTree(
      """
      {
        "catalog": {
          "revisionFingerprint": "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
          "status": "ACTIVE",
          "revision": 1
        },
        "profileMeta": { "id": "site.example" }
      }
      """
    );
    JsonNode changed = reordered.deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) changed.path("catalog")).put("status", "INACTIVE");

    assertThat(service.revisionFingerprint(first)).isEqualTo(service.revisionFingerprint(reordered));
    assertThat(service.revisionFingerprint(changed)).isNotEqualTo(service.revisionFingerprint(first));
  }

  @Test
  void recognitionFingerprintIsCanonicalAndChangesOnlyWithBehavior() throws Exception {
    JsonNode first = objectMapper.readTree(
      """
      {
        "mode": "RULES",
        "rules": {
          "qc-prefix": {
            "ruleType": "SPECIMEN_ID_PREFIX",
            "operand": "QC-"
          }
        }
      }
      """
    );
    JsonNode sameBehavior = objectMapper.readTree(
      """
      {
        "rules": {
          "qc-prefix": {
            "operand": "QC-",
            "ruleType": "SPECIMEN_ID_PREFIX"
          }
        },
        "mode": "RULES"
      }
      """
    );
    JsonNode changedBehavior = first.deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) changedBehavior.path("rules").path("qc-prefix")).put(
        "operand",
        "CTRL-"
      );

    assertThat(service.recognitionFingerprint(first)).isEqualTo(service.recognitionFingerprint(sameBehavior));
    assertThat(service.recognitionFingerprint(changedBehavior)).isNotEqualTo(service.recognitionFingerprint(first));
  }
}
