package org.itech.ahb.file;

import org.itech.ahb.outbox.DeliveryIdentity;

/**
 * Stable identity for one accession delivery, independent of file path or process lifetime.
 *
 * <p>Retained as the FILE transport's entry point; the derivation is shared with every other
 * transport in {@link DeliveryIdentity} and produces byte-identical values to before, so identities
 * already recorded by OpenELIS stay valid.
 */
public final class FileDeliveryIdentity {

  private FileDeliveryIdentity() {}

  public static String contentHash(byte[] content) {
    return DeliveryIdentity.contentHash(content);
  }

  public static String forAccession(String connectionId, String contentHash, String accessionNumber) {
    return DeliveryIdentity.forAccession("file-v1", connectionId, contentHash, accessionNumber);
  }
}
