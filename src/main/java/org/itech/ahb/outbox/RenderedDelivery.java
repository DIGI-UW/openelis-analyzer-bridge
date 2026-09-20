package org.itech.ahb.outbox;

/**
 * One deliverable unit produced from a received message: a single accession's normalized bundle,
 * with the exact bytes that will be POSTed.
 *
 * <p>Those bytes are stored and re-sent unchanged on every automatic retry, so a redelivery carries
 * the same {@code Bundle.identifier} OpenELIS already deduplicates on.
 */
public record RenderedDelivery(
  String deliveryId,
  String accession,
  String fhirJson,
  String connectionId,
  String analyzerId,
  String profileId,
  Integer profileRevision,
  String targetUri
) {}
