package org.itech.ahb.outbox;

import java.time.Instant;
import org.itech.ahb.model.Protocol;
import org.itech.ahb.model.Transport;

/**
 * One outbox row without its payloads.
 *
 * <p>The raw message and rendered bundle are deliberately not fields here: they are clinical content
 * and are read only through the audited payload accessors, so ordinary listing, logging and metrics
 * cannot leak them.
 */
public record OutboxEntry(
  String id,
  OutboxState state,
  String rawHash,
  int rawByteLength,
  int fhirByteLength,
  String fhirHash,
  String connectionId,
  String analyzerId,
  String sourceId,
  Integer sourcePort,
  Protocol protocol,
  Transport transport,
  String protocolHint,
  String profileId,
  Integer profileRevision,
  String accession,
  String targetUri,
  int attempts,
  Instant receivedAt,
  Instant renderedAt,
  Instant nextAttemptAt,
  Instant lastAttemptAt,
  Instant deliveredAt,
  Instant dmqAt,
  Instant leaseUntil,
  String leaseOwner,
  FailureReason failureReason,
  String lastError,
  Integer lastHttpStatus,
  String lastResponseExcerpt,
  String oeReceipt,
  Instant dismissedAt,
  String dismissedBy,
  String retryRequestedBy,
  Instant retryRequestedAt,
  Instant updatedAt,
  /** Shared listener the message arrived on, so a retry resolves its connection the same way. */
  Integer listenerPort
) {
  public OutboxEntry(
    String id,
    OutboxState state,
    String rawHash,
    int rawByteLength,
    int fhirByteLength,
    String fhirHash,
    String connectionId,
    String analyzerId,
    String sourceId,
    Integer sourcePort,
    Protocol protocol,
    Transport transport,
    String protocolHint,
    String profileId,
    Integer profileRevision,
    String accession,
    String targetUri,
    int attempts,
    Instant receivedAt,
    Instant renderedAt,
    Instant nextAttemptAt,
    Instant lastAttemptAt,
    Instant deliveredAt,
    Instant dmqAt,
    Instant leaseUntil,
    String leaseOwner,
    FailureReason failureReason,
    String lastError,
    Integer lastHttpStatus,
    String lastResponseExcerpt,
    String oeReceipt,
    Instant dismissedAt,
    String dismissedBy,
    String retryRequestedBy,
    Instant retryRequestedAt,
    Instant updatedAt
  ) {
    this(id, state, rawHash, rawByteLength, fhirByteLength, fhirHash, connectionId, analyzerId, sourceId, sourcePort,
      protocol, transport, protocolHint, profileId, profileRevision, accession, targetUri, attempts, receivedAt,
      renderedAt, nextAttemptAt, lastAttemptAt, deliveredAt, dmqAt, leaseUntil, leaseOwner, failureReason, lastError,
      lastHttpStatus, lastResponseExcerpt, oeReceipt, dismissedAt, dismissedBy, retryRequestedBy, retryRequestedAt,
      updatedAt, null);
  }

  /** Seconds since the bridge received the message, the number an operator triages by. */
  public long ageSeconds(Instant now) {
    return receivedAt == null ? 0 : Math.max(0, now.getEpochSecond() - receivedAt.getEpochSecond());
  }
}
