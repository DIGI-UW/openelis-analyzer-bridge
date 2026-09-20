package org.itech.ahb.outbox;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.itech.ahb.model.Protocol;

/**
 * Stable identity for one accession delivery, derived from content rather than minted per attempt.
 *
 * <p>The identity is what OpenELIS deduplicates on: it arrives as {@code Bundle.identifier} and OE
 * records it transactionally when it accepts the result, so a repeated delivery returns the original
 * receipt instead of creating a second clinical result. That only works if the same received bytes
 * always produce the same identity, including across a bridge restart. A per-build random UUID
 * cannot do that, which is why every transport now derives the identity here.
 *
 * <p>The FILE prefix and digest layout are unchanged from the original FILE-only implementation, so
 * identities already recorded by OpenELIS for file deliveries stay valid.
 */
public final class DeliveryIdentity {

  private static final String ASTM_PREFIX = "astm-v1";
  private static final String HL7_PREFIX = "hl7-v1";

  /** Tabular content keeps the original FILE prefix whether it arrives by watcher or by HTTP. */
  private static final String FILE_PREFIX = "file-v1";

  private DeliveryIdentity() {}

  /** SHA-256 of the received bytes, as lowercase hex. */
  public static String contentHash(byte[] content) {
    if (content == null) {
      throw new IllegalArgumentException("delivery identity requires content");
    }
    return HexFormat.of().formatHex(sha256().digest(content));
  }

  /** SHA-256 of the received text's UTF-8 encoding, as lowercase hex. */
  public static String contentHash(String content) {
    if (content == null) {
      throw new IllegalArgumentException("delivery identity requires content");
    }
    return contentHash(content.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * The identity prefix for a protocol. Prefixes are versioned so a future change to the derivation
   * is visibly a different identity rather than a silent collision with existing OpenELIS receipts.
   */
  public static String prefixFor(Protocol protocol) {
    if (protocol == null) {
      throw new IllegalArgumentException("delivery identity requires a protocol");
    }
    return switch (protocol) {
      case ASTM -> ASTM_PREFIX;
      case HL7 -> HL7_PREFIX;
      case CSV -> FILE_PREFIX;
      case UNKNOWN -> throw new IllegalArgumentException(
        "delivery identity requires a recognized protocol; UNKNOWN messages are rejected before delivery"
      );
    };
  }

  /** Derive the identity for one accession within a received message. */
  public static String forAccession(
    Protocol protocol,
    String connectionId,
    String contentHash,
    String accessionNumber
  ) {
    return forAccession(prefixFor(protocol), connectionId, contentHash, accessionNumber);
  }

  /**
   * Derive the identity for one accession under an explicit prefix.
   *
   * <p>Components are length-prefixed before hashing so that no rearrangement of connection,
   * content hash and accession can produce the same digest as a different triple.
   */
  public static String forAccession(String prefix, String connectionId, String contentHash, String accessionNumber) {
    if (prefix == null || prefix.isBlank()) {
      throw new IllegalArgumentException("delivery identity requires a prefix");
    }
    MessageDigest digest = sha256();
    for (String component : new String[] { connectionId, contentHash, accessionNumber }) {
      if (component == null || component.isBlank()) {
        throw new IllegalArgumentException("delivery requires connection, content hash, and accession");
      }
      byte[] bytes = component.getBytes(StandardCharsets.UTF_8);
      digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
      digest.update(bytes);
    }
    return prefix + ":" + HexFormat.of().formatHex(digest.digest());
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is required for delivery identity", exception);
    }
  }
}
