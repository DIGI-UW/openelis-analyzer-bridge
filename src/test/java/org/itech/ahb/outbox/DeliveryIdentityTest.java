package org.itech.ahb.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.itech.ahb.file.FileDeliveryIdentity;
import org.itech.ahb.model.Protocol;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The delivery identity is the key OpenELIS deduplicates on, so these tests pin the two properties
 * that make at-least-once delivery safe: the same received content always yields the same identity,
 * and different content never collides.
 */
class DeliveryIdentityTest {

  private static final String CONNECTION = "bridge-connection-7f3c";
  private static final String ACCESSION = "ACC-1";

  @Test
  @DisplayName("is stable across calls for the same content")
  void isStableForTheSameContent() {
    String raw = "H|\\^&|||GeneXpert^1.0|||||||P|1|20260919\rR|1|^^^MTB|NEG||||F\r";
    String first = DeliveryIdentity.forAccession(
      Protocol.ASTM,
      CONNECTION,
      DeliveryIdentity.contentHash(raw),
      ACCESSION
    );
    String second = DeliveryIdentity.forAccession(
      Protocol.ASTM,
      CONNECTION,
      DeliveryIdentity.contentHash(raw),
      ACCESSION
    );
    assertEquals(first, second, "a redelivery after a restart must carry the identity of the first attempt");
    assertTrue(first.startsWith("astm-v1:"));
  }

  @Test
  @DisplayName("changes when any component changes")
  void changesWithEveryComponent() {
    String hash = DeliveryIdentity.contentHash("payload");
    String base = DeliveryIdentity.forAccession(Protocol.ASTM, CONNECTION, hash, ACCESSION);

    assertNotEquals(base, DeliveryIdentity.forAccession(Protocol.ASTM, "other-connection", hash, ACCESSION));
    assertNotEquals(base, DeliveryIdentity.forAccession(Protocol.ASTM, CONNECTION, hash, "ACC-2"));
    assertNotEquals(
      base,
      DeliveryIdentity.forAccession(Protocol.ASTM, CONNECTION, DeliveryIdentity.contentHash("other payload"), ACCESSION)
    );
    assertNotEquals(
      base,
      DeliveryIdentity.forAccession(Protocol.HL7, CONNECTION, hash, ACCESSION),
      "protocols must not share an identity for otherwise identical content"
    );
  }

  @Test
  @DisplayName("cannot be forged by shifting a boundary between components")
  void componentsAreLengthPrefixed() {
    String a = DeliveryIdentity.forAccession(Protocol.ASTM, "ab", "cd", "ef");
    String b = DeliveryIdentity.forAccession(Protocol.ASTM, "a", "bcd", "ef");
    assertNotEquals(a, b, "concatenation without length prefixes would collide here");
  }

  @Test
  @DisplayName("keeps the FILE identity byte-identical to the original implementation")
  void fileIdentityIsUnchanged() {
    String hash = FileDeliveryIdentity.contentHash("a,b,c\n1,2,3\n".getBytes(StandardCharsets.UTF_8));
    String viaFile = FileDeliveryIdentity.forAccession(CONNECTION, hash, ACCESSION);
    String viaShared = DeliveryIdentity.forAccession(Protocol.CSV, CONNECTION, hash, ACCESSION);
    assertEquals(
      viaFile,
      viaShared,
      "identities already recorded by OpenELIS for file deliveries must keep resolving to the same value"
    );
    assertTrue(viaFile.startsWith("file-v1:"));
  }

  @Test
  @DisplayName("hashes text and bytes identically")
  void textAndBytesAgree() {
    String text = "sample content";
    assertEquals(
      DeliveryIdentity.contentHash(text),
      DeliveryIdentity.contentHash(text.getBytes(StandardCharsets.UTF_8))
    );
  }

  @Test
  @DisplayName("refuses incomplete or unidentifiable input")
  void refusesIncompleteInput() {
    String hash = DeliveryIdentity.contentHash("payload");
    assertThrows(
      IllegalArgumentException.class,
      () -> DeliveryIdentity.forAccession(Protocol.ASTM, null, hash, ACCESSION)
    );
    assertThrows(
      IllegalArgumentException.class,
      () -> DeliveryIdentity.forAccession(Protocol.ASTM, CONNECTION, hash, " ")
    );
    assertThrows(
      IllegalArgumentException.class,
      () -> DeliveryIdentity.forAccession(Protocol.UNKNOWN, CONNECTION, hash, ACCESSION),
      "an unrecognized protocol must fail loudly rather than mint an unroutable identity"
    );
    assertThrows(IllegalArgumentException.class, () -> DeliveryIdentity.contentHash((String) null));
  }
}
